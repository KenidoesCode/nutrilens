"""Density resolution: exact hits, alias hits, fallbacks and refusals."""

from __future__ import annotations

import pytest

from nutrilens_ml.density.engine import DensityEngine, DensityUnavailableError
from nutrilens_ml.domain import FoodCategory


@pytest.fixture
def engine(catalog):
    return DensityEngine(catalog)


class TestLookup:
    def test_exact_key(self, engine):
        record = engine.lookup("rice")
        assert record.food_key == "rice"
        assert record.density_g_per_ml == pytest.approx(0.85)
        assert record.is_fallback is False

    def test_display_name_is_case_insensitive(self, engine):
        assert engine.lookup("Leafy Greens").food_key == "leafy_greens"

    def test_alias_resolves_to_canonical_record(self, engine):
        assert engine.lookup("curd").food_key == "yogurt"
        assert engine.lookup("pappu").food_key == "dal"

    def test_records_dataset_version_as_source(self, engine, catalog):
        assert engine.lookup("rice").source_version == catalog.dataset_version


class TestFallback:
    def test_unknown_food_uses_category_default(self, engine, catalog):
        record = engine.lookup("unheard-of dish", FoodCategory.SOLID)
        assert record.is_fallback is True
        assert record.density_g_per_ml == catalog.default_density(FoodCategory.SOLID)
        assert record.confidence == DensityEngine.CATEGORY_FALLBACK_CONFIDENCE

    def test_fallback_confidence_is_below_any_catalog_entry(self, engine, catalog):
        worst_catalog_confidence = min(r.density_confidence for r in catalog.all())
        assert DensityEngine.CATEGORY_FALLBACK_CONFIDENCE < worst_catalog_confidence

    def test_refuses_without_category(self, engine):
        with pytest.raises(DensityUnavailableError):
            engine.lookup("unheard-of dish")

    def test_refuses_when_fallback_disabled(self, engine):
        with pytest.raises(DensityUnavailableError):
            engine.lookup("unheard-of dish", FoodCategory.SOLID, allow_category_fallback=False)


class TestToMass:
    def test_applies_catalog_density(self, engine):
        estimate = engine.to_mass("rice", 180.0)
        assert estimate.mass_g == pytest.approx(153.0)
        assert estimate.is_fallback_density is False

    def test_flags_fallback_density_on_the_estimate(self, engine):
        estimate = engine.to_mass("mystery stew", 200.0, FoodCategory.SEMISOLID)
        assert estimate.is_fallback_density is True
        assert estimate.density_source.startswith("category-default@")

    def test_rejects_zero_volume(self, engine):
        # A zero-mass food item is meaningless downstream, so it must not be
        # constructible rather than silently flowing into a meal record.
        with pytest.raises(ValueError):
            engine.to_mass("rice", 0.0)
