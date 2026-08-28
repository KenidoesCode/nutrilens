"""Segmentation behaviour on controlled synthetic scenes."""

from __future__ import annotations

import numpy as np
import pytest

from nutrilens_ml.preprocessing.segmentation import (
    _circular_mean_hue,
    _connected_components,
    _kmeans,
    segment_plate,
)


class TestSegmentPlate:
    def test_separates_three_distinct_dishes(self, plate_array):
        regions = segment_plate(plate_array)
        assert len(regions) == 3

    def test_regions_are_ordered_by_descending_area(self, plate_array):
        regions = segment_plate(plate_array)
        areas = [region.area_ratio for region in regions]
        assert areas == sorted(areas, reverse=True)

    def test_is_deterministic(self, plate_array):
        first = segment_plate(plate_array)
        second = segment_plate(plate_array)
        assert [r.area_ratio for r in first] == [r.area_ratio for r in second]
        assert [r.mean_hue_deg for r in first] == [r.mean_hue_deg for r in second]

    def test_recovers_the_green_dish_hue(self, plate_array):
        regions = segment_plate(plate_array)
        hues = [r.mean_hue_deg for r in regions]
        assert any(90 <= hue <= 150 for hue in hues), hues

    def test_plate_ratio_is_a_share_of_the_frame(self, plate_array):
        for region in segment_plate(plate_array):
            assert 0.0 < region.plate_area_ratio <= 1.0
            assert region.area_ratio <= region.plate_area_ratio + 1e-9

    def test_uniform_frame_yields_no_regions_or_one_blob(self, plate_array):
        # A featureless frame must never be split into several "dishes".
        regions = segment_plate(np.full((256, 256, 3), 200, dtype=np.uint8))
        assert len(regions) <= 1

    def test_rejects_non_rgb_input(self, plate_array):
        with pytest.raises(ValueError):
            segment_plate(np.zeros((64, 64), dtype=np.uint8))


class TestKMeans:
    def test_recovers_well_separated_clusters(self, plate_array):
        features = np.concatenate(
            [
                np.zeros((30, 2), dtype=np.float32),
                np.full((30, 2), 10.0, dtype=np.float32),
            ]
        )
        labels = _kmeans(features, k=2, seed=7)
        assert len(set(labels[:30])) == 1
        assert len(set(labels[30:])) == 1
        assert labels[0] != labels[-1]

    def test_handles_k_greater_than_sample_count(self, plate_array):
        features = np.zeros((3, 2), dtype=np.float32)
        assert _kmeans(features, k=10, seed=1).shape == (3,)

    def test_same_seed_gives_same_labels(self, plate_array):
        rng = np.random.default_rng(0)
        features = rng.normal(size=(80, 3)).astype(np.float32)
        assert np.array_equal(_kmeans(features, 4, seed=3), _kmeans(features, 4, seed=3))


class TestConnectedComponents:
    def test_splits_disjoint_blobs(self, plate_array):
        mask = np.zeros((10, 10), dtype=bool)
        mask[1:3, 1:3] = True
        mask[7:9, 7:9] = True
        assert len(_connected_components(mask)) == 2

    def test_diagonal_touch_is_not_connected(self, plate_array):
        mask = np.zeros((4, 4), dtype=bool)
        mask[0, 0] = True
        mask[1, 1] = True
        assert len(_connected_components(mask)) == 2

    def test_empty_mask_has_no_components(self, plate_array):
        assert _connected_components(np.zeros((5, 5), dtype=bool)) == []


class TestCircularHue:
    def test_averages_across_the_wraparound(self, plate_array):
        mean, _ = _circular_mean_hue(np.array([350.0, 10.0]))
        assert mean == pytest.approx(0.0, abs=1e-6) or mean == pytest.approx(360.0, abs=1e-6)

    def test_identical_hues_have_no_spread(self, plate_array):
        _, spread = _circular_mean_hue(np.array([120.0, 120.0, 120.0]))
        assert spread == pytest.approx(0.0, abs=1e-3)

    def test_opposing_hues_have_large_spread(self, plate_array):
        _, spread = _circular_mean_hue(np.array([0.0, 180.0]))
        assert spread > 90.0
