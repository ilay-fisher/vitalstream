"""Reciprocal Rank Fusion: score = sum over rankings of 1 / (k + rank)."""

import pytest

from copilot.rag.fusion import reciprocal_rank_fusion


def test_single_ranking_preserves_order_and_uses_formula() -> None:
    fused = reciprocal_rank_fusion([["a", "b", "c"]], k=60)
    assert [item for item, _ in fused] == ["a", "b", "c"]
    assert fused[0][1] == pytest.approx(1 / 61)
    assert fused[2][1] == pytest.approx(1 / 63)


def test_item_in_both_rankings_beats_item_in_one() -> None:
    fused = dict(reciprocal_rank_fusion([["a", "b"], ["c", "b"]], k=60))
    assert fused["b"] == pytest.approx(1 / 62 + 1 / 62)
    assert fused["b"] > fused["a"]
    assert fused["b"] > fused["c"]


def test_agreement_outranks_a_single_first_place() -> None:
    fused = reciprocal_rank_fusion([["x", "y", "z"], ["y", "z", "x"]])
    assert fused[0][0] == "y"


def test_ties_break_by_first_appearance() -> None:
    fused = reciprocal_rank_fusion([["a"], ["b"]])
    assert [item for item, _ in fused] == ["a", "b"]


def test_empty_inputs() -> None:
    assert reciprocal_rank_fusion([]) == []
    assert reciprocal_rank_fusion([[], []]) == []


def test_duplicate_within_one_ranking_counts_best_rank_only() -> None:
    fused = dict(reciprocal_rank_fusion([["a", "b", "a"]], k=60))
    assert fused["a"] == pytest.approx(1 / 61)


def test_k_must_be_positive() -> None:
    with pytest.raises(ValueError, match="k"):
        reciprocal_rank_fusion([["a"]], k=0)
