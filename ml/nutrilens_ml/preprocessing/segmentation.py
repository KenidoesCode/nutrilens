"""Colour-space segmentation of a plated meal into candidate food regions.

This is a classical computer-vision stage, not a learned one. It runs
entirely on the device/server CPU with numpy and no model weights, which
makes it a dependable fallback when no neural network is available.

Pipeline:

1. convert RGB to HSV,
2. estimate the plate/background mask (large, bright, low-saturation, and
   biased towards the image border),
3. k-means cluster the remaining foreground pixels in a colour+position
   feature space,
4. split each cluster into spatially connected components,
5. drop specks and return the regions ordered by area.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass

import numpy as np

from ..domain import BoundingBox

# Working resolution for segmentation. Small enough to keep connected-component
# labelling cheap, large enough to separate side dishes on a thali.
WORK_SIZE = 128
MIN_REGION_AREA_RATIO = 0.012
# A detected "plate" outside this band is not believable as crockery in frame.
PLATE_RATIO_FLOOR = 0.10
PLATE_RATIO_CEILING = 0.95
# Below this colour/brightness dispersion the frame carries no distinguishable
# content -- a lens cap, a blank wall, a wholly blown-out exposure. Reporting
# food there would be inventing it.
FEATURELESS_DISPERSION = 0.012
MAX_REGIONS = 8
DEFAULT_CLUSTERS = 5
KMEANS_MAX_ITERATIONS = 25
KMEANS_TOLERANCE = 1e-4


@dataclass(frozen=True, slots=True)
class Region:
    """A spatially connected group of similarly-coloured foreground pixels."""

    mask: np.ndarray
    area_ratio: float
    bbox: BoundingBox
    mean_hue_deg: float
    mean_saturation: float
    mean_value: float
    hue_std_deg: float
    texture_score: float
    plate_area_ratio: float
    """Share of the frame occupied by the plate surface (bare plate + food)."""

    @property
    def area_ratio_of_plate(self) -> float:
        """Region area as a share of the detected plate, not the whole frame."""
        if self.plate_area_ratio <= 0:
            return self.area_ratio
        return min(1.0, self.area_ratio / self.plate_area_ratio)


def _rgb_to_hsv(rgb01: np.ndarray) -> np.ndarray:
    """Vectorised RGB->HSV. Hue in degrees, saturation and value in [0, 1]."""
    r, g, b = rgb01[..., 0], rgb01[..., 1], rgb01[..., 2]
    maxc = np.max(rgb01, axis=-1)
    minc = np.min(rgb01, axis=-1)
    delta = maxc - minc

    hue = np.zeros_like(maxc)
    nonzero = delta > 1e-9
    with np.errstate(invalid="ignore", divide="ignore"):
        rc = np.where(nonzero, (maxc - r) / np.where(nonzero, delta, 1.0), 0.0)
        gc = np.where(nonzero, (maxc - g) / np.where(nonzero, delta, 1.0), 0.0)
        bc = np.where(nonzero, (maxc - b) / np.where(nonzero, delta, 1.0), 0.0)

    hue = np.where(maxc == r, bc - gc, hue)
    hue = np.where((maxc == g) & (maxc != r), 2.0 + rc - bc, hue)
    hue = np.where((maxc == b) & (maxc != r) & (maxc != g), 4.0 + gc - rc, hue)
    hue = (hue / 6.0) % 1.0
    hue = np.where(nonzero, hue * 360.0, 0.0)

    saturation = np.where(maxc > 1e-9, delta / np.where(maxc > 1e-9, maxc, 1.0), 0.0)
    return np.stack([hue, saturation, maxc], axis=-1)


def _downsample(image: np.ndarray, size: int) -> np.ndarray:
    """Nearest-neighbour box downsample. Avoids a Pillow round-trip."""
    h, w = image.shape[:2]
    rows = np.linspace(0, h - 1, size).round().astype(np.intp)
    cols = np.linspace(0, w - 1, size).round().astype(np.intp)
    return image[np.ix_(rows, cols)]


def _local_texture(value_channel: np.ndarray) -> np.ndarray:
    """Per-pixel local contrast, approximated by a 3x3 neighbourhood range.

    Smooth foods (dal, yogurt) score low; granular foods (rice) score high.
    """
    padded = np.pad(value_channel, 1, mode="edge")
    stack = np.stack(
        [padded[dy : dy + value_channel.shape[0], dx : dx + value_channel.shape[1]]
         for dy in range(3)
         for dx in range(3)],
        axis=0,
    )
    return stack.max(axis=0) - stack.min(axis=0)


def _scene_dispersion(hsv: np.ndarray) -> float:
    """How much the frame varies in colourfulness and brightness."""
    return max(float(hsv[..., 1].std()), float(hsv[..., 2].std()))


def _estimate_scene(hsv: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Split the frame into (food foreground, plate surface).

    A plate is characteristically bright and unsaturated. Rather than assuming
    white crockery, the threshold adapts to the image: pixels whose saturation
    falls in the lowest third *and* whose brightness is above the median are
    treated as bare plate. Border pixels are additionally treated as table,
    because the surface the plate sits on dominates the frame edge.

    The plate *surface* is the bare-plate pixels plus the food sitting on it --
    that union is what the portion estimator scales against a known plate
    diameter, so it must not be confused with the food mask itself.
    """
    saturation = hsv[..., 1]
    value = hsv[..., 2]

    sat_threshold = float(np.quantile(saturation, 0.35))
    val_threshold = float(np.quantile(value, 0.5))

    plate_like = (saturation <= sat_threshold) & (value >= val_threshold)

    size = hsv.shape[0]
    yy, xx = np.mgrid[0:size, 0:size]
    centre = (size - 1) / 2.0
    radial = np.sqrt((yy - centre) ** 2 + (xx - centre) ** 2) / centre
    table = radial > 0.85

    background = plate_like | (table & (saturation <= sat_threshold * 1.6))
    foreground = ~background

    # If the adaptive threshold swallowed nearly everything, the assumption did
    # not hold (e.g. a dark bowl on a dark table); fall back to the whole frame
    # minus the outermost ring.
    if foreground.mean() < 0.08:
        foreground = radial <= 0.9

    # The plate surface is bare plate plus the food on it, excluding the table
    # ring. Clamped to a plausible band so a pathological mask cannot drive the
    # scale factor to an extreme.
    plate = (plate_like & ~table) | foreground
    plate_ratio = float(plate.mean())
    if plate_ratio < PLATE_RATIO_FLOOR or plate_ratio > PLATE_RATIO_CEILING:
        plate = radial <= 0.9
    return foreground, plate


def _kmeans(features: np.ndarray, k: int, seed: int) -> np.ndarray:
    """Deterministic k-means++ style initialisation followed by Lloyd's algorithm.

    Determinism matters: the same photo must produce the same analysis, both
    for user trust and for reproducible tests.
    """
    n_samples = features.shape[0]
    k = max(1, min(k, n_samples))
    rng = np.random.default_rng(seed)

    centroids = np.empty((k, features.shape[1]), dtype=np.float32)
    centroids[0] = features[rng.integers(n_samples)]
    closest = np.sum((features - centroids[0]) ** 2, axis=1)
    for index in range(1, k):
        total = float(closest.sum())
        if total <= 0:
            centroids[index] = features[rng.integers(n_samples)]
        else:
            probabilities = closest / total
            centroids[index] = features[rng.choice(n_samples, p=probabilities)]
        closest = np.minimum(closest, np.sum((features - centroids[index]) ** 2, axis=1))

    labels = np.zeros(n_samples, dtype=np.intp)
    for _ in range(KMEANS_MAX_ITERATIONS):
        distances = np.sum((features[:, None, :] - centroids[None, :, :]) ** 2, axis=2)
        new_labels = np.argmin(distances, axis=1)
        if np.array_equal(new_labels, labels):
            break
        labels = new_labels
        shift = 0.0
        for index in range(k):
            members = features[labels == index]
            if members.size == 0:
                continue
            updated = members.mean(axis=0)
            shift = max(shift, float(np.abs(updated - centroids[index]).max()))
            centroids[index] = updated
        if shift < KMEANS_TOLERANCE:
            break
    return labels


def _connected_components(mask: np.ndarray) -> list[np.ndarray]:
    """4-connected component labelling via BFS. Returns one boolean mask each."""
    visited = np.zeros_like(mask, dtype=bool)
    height, width = mask.shape
    components: list[np.ndarray] = []

    for start_y in range(height):
        for start_x in range(width):
            if not mask[start_y, start_x] or visited[start_y, start_x]:
                continue
            component = np.zeros_like(mask, dtype=bool)
            queue: deque[tuple[int, int]] = deque([(start_y, start_x)])
            visited[start_y, start_x] = True
            while queue:
                y, x = queue.popleft()
                component[y, x] = True
                for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < height and 0 <= nx < width:
                        if mask[ny, nx] and not visited[ny, nx]:
                            visited[ny, nx] = True
                            queue.append((ny, nx))
            components.append(component)
    return components


def _circular_mean_hue(
    hues_deg: np.ndarray, weights: np.ndarray | None = None
) -> tuple[float, float]:
    """Mean and spread of hue on the colour circle. Returns (mean_deg, std_deg)."""
    radians = np.deg2rad(hues_deg)
    if weights is None:
        sin_mean = float(np.sin(radians).mean())
        cos_mean = float(np.cos(radians).mean())
    else:
        total = float(weights.sum()) or 1.0
        sin_mean = float((np.sin(radians) * weights).sum() / total)
        cos_mean = float((np.cos(radians) * weights).sum() / total)
    mean_deg = float(np.rad2deg(np.arctan2(sin_mean, cos_mean)) % 360.0)
    resultant = min(1.0, float(np.hypot(sin_mean, cos_mean)))
    # Circular standard deviation, capped so a uniform hue distribution does
    # not produce an infinite spread.
    spread = float(np.rad2deg(np.sqrt(-2.0 * np.log(max(resultant, 1e-6)))))
    return mean_deg, min(spread, 180.0)


def segment_plate(rgb: np.ndarray, *, seed: int = 20240501) -> list[Region]:
    """Segment a prepared RGB image into candidate food regions."""
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise ValueError("segment_plate expects an HxWx3 RGB array")

    small = _downsample(rgb, WORK_SIZE).astype(np.float32) / 255.0
    hsv = _rgb_to_hsv(small)

    # Guard before any clustering: k-means will happily partition noise, and a
    # featureless frame must yield nothing rather than a confident blob.
    if _scene_dispersion(hsv) < FEATURELESS_DISPERSION:
        return []

    texture = _local_texture(hsv[..., 2])
    foreground, plate = _estimate_scene(hsv)
    plate_area_ratio = float(plate.mean())
    if not foreground.any() or plate_area_ratio <= 0:
        return []

    coords = np.argwhere(foreground)
    hue_rad = np.deg2rad(hsv[..., 0][foreground])
    saturation = hsv[..., 1][foreground]
    value = hsv[..., 2][foreground]

    # Hue is encoded as its unit-circle coordinates so that red near 0 and red
    # near 360 cluster together. Position is included with a low weight so a
    # dish is not split across the plate purely by colour noise.
    features = np.stack(
        [
            np.cos(hue_rad) * saturation * 2.0,
            np.sin(hue_rad) * saturation * 2.0,
            value * 1.2,
            coords[:, 0] / WORK_SIZE * 0.9,
            coords[:, 1] / WORK_SIZE * 0.9,
        ],
        axis=1,
    ).astype(np.float32)

    labels = _kmeans(features, DEFAULT_CLUSTERS, seed)

    regions: list[Region] = []
    total_pixels = float(WORK_SIZE * WORK_SIZE)
    for cluster in np.unique(labels):
        cluster_mask = np.zeros((WORK_SIZE, WORK_SIZE), dtype=bool)
        selected = coords[labels == cluster]
        cluster_mask[selected[:, 0], selected[:, 1]] = True
        for component in _connected_components(cluster_mask):
            area_ratio = float(component.sum()) / total_pixels
            if area_ratio < MIN_REGION_AREA_RATIO:
                continue
            regions.append(
                _build_region(component, hsv, texture, area_ratio, plate_area_ratio)
            )

    regions = _merge_adjacent_regions(regions, hsv, texture, plate_area_ratio)
    regions = [r for r in regions if r.area_ratio >= MIN_REGION_AREA_RATIO]
    regions.sort(key=lambda region: region.area_ratio, reverse=True)
    return regions[:MAX_REGIONS]


def _dilate(mask: np.ndarray) -> np.ndarray:
    """3x3 binary dilation, used to test spatial adjacency of two components."""
    padded = np.pad(mask, 1, mode="constant", constant_values=False)
    result = np.zeros_like(mask)
    for dy in range(3):
        for dx in range(3):
            result |= padded[dy : dy + mask.shape[0], dx : dx + mask.shape[1]]
    return result


def _perceptually_close(a: Region, b: Region) -> bool:
    """Whether two adjacent components plausibly belong to the same dish."""
    hue_gap = min(
        abs(a.mean_hue_deg - b.mean_hue_deg),
        360.0 - abs(a.mean_hue_deg - b.mean_hue_deg),
    )
    # A weakly saturated region has an unstable hue, so colourfulness is
    # compared first and hue only matters once both regions have real colour.
    saturation_gap = abs(a.mean_saturation - b.mean_saturation)
    value_gap = abs(a.mean_value - b.mean_value)
    if saturation_gap > 0.18 or value_gap > 0.2:
        return False
    if min(a.mean_saturation, b.mean_saturation) < 0.15:
        return True
    return hue_gap <= 22.0


def _merge_adjacent_regions(
    regions: list[Region],
    hsv: np.ndarray,
    texture: np.ndarray,
    plate_area_ratio: float,
) -> list[Region]:
    """Union spatially adjacent, perceptually similar components.

    k-means partitions colour space, so a single dish that straddles a cluster
    boundary comes back as two components. Merging them again keeps one dish
    from being reported (and double-counted) as two foods.
    """
    if len(regions) < 2:
        return regions

    parent = list(range(len(regions)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    def union(a: int, b: int) -> None:
        root_a, root_b = find(a), find(b)
        if root_a != root_b:
            parent[root_b] = root_a

    dilated = [_dilate(region.mask) for region in regions]
    for i in range(len(regions)):
        for j in range(i + 1, len(regions)):
            if not (dilated[i] & regions[j].mask).any():
                continue
            if _perceptually_close(regions[i], regions[j]):
                union(i, j)

    grouped: dict[int, np.ndarray] = {}
    for index, region in enumerate(regions):
        root = find(index)
        if root in grouped:
            grouped[root] = grouped[root] | region.mask
        else:
            grouped[root] = region.mask.copy()

    total_pixels = float(WORK_SIZE * WORK_SIZE)
    merged = [
        _build_region(mask, hsv, texture, float(mask.sum()) / total_pixels, plate_area_ratio)
        for mask in grouped.values()
    ]
    return merged


def _build_region(
    component: np.ndarray,
    hsv: np.ndarray,
    texture: np.ndarray,
    area_ratio: float,
    plate_area_ratio: float,
) -> Region:
    hues = hsv[..., 0][component]
    saturations = hsv[..., 1][component]
    values = hsv[..., 2][component]
    mean_hue, hue_std = _circular_mean_hue(hues, saturations)

    ys, xs = np.nonzero(component)
    y0, y1 = int(ys.min()), int(ys.max())
    x0, x1 = int(xs.min()), int(xs.max())
    bbox = BoundingBox(
        x=x0 / WORK_SIZE,
        y=y0 / WORK_SIZE,
        width=max((x1 - x0 + 1) / WORK_SIZE, 1.0 / WORK_SIZE),
        height=max((y1 - y0 + 1) / WORK_SIZE, 1.0 / WORK_SIZE),
    )

    return Region(
        mask=component,
        area_ratio=area_ratio,
        bbox=bbox,
        mean_hue_deg=mean_hue,
        mean_saturation=float(saturations.mean()),
        mean_value=float(values.mean()),
        hue_std_deg=hue_std,
        texture_score=float(texture[component].mean()),
        plate_area_ratio=plate_area_ratio,
    )
