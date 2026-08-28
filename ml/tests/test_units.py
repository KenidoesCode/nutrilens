"""Unit conversion: exactness, invertibility and boundary behaviour."""

from __future__ import annotations

import math

import pytest

from nutrilens_ml.density.units import (
    MAX_PLAUSIBLE_DENSITY,
    MIN_PLAUSIBLE_DENSITY,
    UnitConversionError,
    cups_to_ml,
    grams_to_ml,
    ml_to_cups,
    ml_to_grams,
    ounces_to_grams,
    validate_density,
)


class TestMlToGrams:
    def test_multiplies_volume_by_density(self):
        assert ml_to_grams(180.0, 0.85) == pytest.approx(153.0)

    def test_water_is_one_to_one(self):
        assert ml_to_grams(250.0, 1.0) == pytest.approx(250.0)

    def test_zero_volume_is_permitted(self):
        assert ml_to_grams(0.0, 0.85) == 0.0

    @pytest.mark.parametrize("volume", [-1.0, float("nan"), float("inf")])
    def test_rejects_invalid_volume(self, volume):
        with pytest.raises(UnitConversionError):
            ml_to_grams(volume, 1.0)

    def test_rejects_non_numeric_volume(self):
        with pytest.raises(UnitConversionError):
            ml_to_grams("180", 1.0)  # type: ignore[arg-type]

    def test_rejects_boolean_volume(self):
        # bool is a subclass of int; silently treating True as 1 ml would be a bug.
        with pytest.raises(UnitConversionError):
            ml_to_grams(True, 1.0)  # type: ignore[arg-type]


class TestGramsToMl:
    def test_divides_mass_by_density(self):
        assert grams_to_ml(153.0, 0.85) == pytest.approx(180.0)

    def test_round_trips_with_ml_to_grams(self):
        for volume in (1.0, 37.5, 180.0, 2999.0):
            mass = ml_to_grams(volume, 0.92)
            assert grams_to_ml(mass, 0.92) == pytest.approx(volume, rel=1e-12)

    def test_rejects_zero_density(self):
        with pytest.raises(UnitConversionError):
            grams_to_ml(100.0, 0.0)


class TestDensityValidation:
    @pytest.mark.parametrize("density", [MIN_PLAUSIBLE_DENSITY, 1.0, MAX_PLAUSIBLE_DENSITY])
    def test_accepts_boundary_values(self, density):
        assert validate_density(density) == density

    @pytest.mark.parametrize(
        "density",
        [
            MIN_PLAUSIBLE_DENSITY - 1e-9,
            MAX_PLAUSIBLE_DENSITY + 1e-9,
            -1.0,
            0.0,
            math.inf,
            math.nan,
        ],
    )
    def test_rejects_out_of_range(self, density):
        with pytest.raises(UnitConversionError):
            validate_density(density)


class TestHouseholdUnits:
    def test_cup_round_trip(self):
        assert ml_to_cups(cups_to_ml(1.5)) == pytest.approx(1.5)

    def test_one_cup_is_236_ml(self):
        assert cups_to_ml(1.0) == pytest.approx(236.5882365)

    def test_ounce_conversion(self):
        assert ounces_to_grams(4.0) == pytest.approx(113.3980925)
