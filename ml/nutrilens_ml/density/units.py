"""Pure unit-conversion helpers.

Isolated from presentation and transport so the conversions can be unit
tested exhaustively and reused by any caller.
"""

from __future__ import annotations

import math

ML_PER_US_CUP = 236.5882365
GRAMS_PER_OUNCE = 28.349523125

# Physically implausible densities are rejected outright: aerogel-light foods
# still sit above 0.05 g/ml and nothing edible approaches the density of lead.
MIN_PLAUSIBLE_DENSITY = 0.05
MAX_PLAUSIBLE_DENSITY = 3.0


class UnitConversionError(ValueError):
    """Raised when a conversion input is missing, non-finite or out of range."""


def _require_finite_positive(value: float, name: str, *, allow_zero: bool = False) -> float:
    if value is None or isinstance(value, bool) or not isinstance(value, (int, float)):
        raise UnitConversionError(f"{name} must be a number, got {value!r}")
    numeric = float(value)
    if not math.isfinite(numeric):
        raise UnitConversionError(f"{name} must be finite, got {numeric}")
    if numeric < 0 or (numeric == 0 and not allow_zero):
        raise UnitConversionError(f"{name} must be positive, got {numeric}")
    return numeric


def validate_density(density_g_per_ml: float) -> float:
    density = _require_finite_positive(density_g_per_ml, "density_g_per_ml")
    if not MIN_PLAUSIBLE_DENSITY <= density <= MAX_PLAUSIBLE_DENSITY:
        raise UnitConversionError(
            f"density_g_per_ml {density} is outside the plausible range "
            f"[{MIN_PLAUSIBLE_DENSITY}, {MAX_PLAUSIBLE_DENSITY}]"
        )
    return density


def ml_to_grams(volume_ml: float, density_g_per_ml: float) -> float:
    """mass = volume x density."""
    volume = _require_finite_positive(volume_ml, "volume_ml", allow_zero=True)
    return volume * validate_density(density_g_per_ml)


def grams_to_ml(mass_g: float, density_g_per_ml: float) -> float:
    """volume = mass / density."""
    mass = _require_finite_positive(mass_g, "mass_g", allow_zero=True)
    return mass / validate_density(density_g_per_ml)


def ml_to_cups(volume_ml: float) -> float:
    return _require_finite_positive(volume_ml, "volume_ml", allow_zero=True) / ML_PER_US_CUP


def cups_to_ml(cups: float) -> float:
    return _require_finite_positive(cups, "cups", allow_zero=True) * ML_PER_US_CUP


def ounces_to_grams(ounces: float) -> float:
    return _require_finite_positive(ounces, "ounces", allow_zero=True) * GRAMS_PER_OUNCE
