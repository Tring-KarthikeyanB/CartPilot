"""Catalogue search and comparison. Every result is a real catalogue product; nothing is generated."""

import math
import re
from decimal import Decimal
from enum import Enum

from app.core.errors import InvalidInputError
from app.core.money import money
from app.repositories.product_repository import ProductRepository
from app.schemas.product import Category, CategoryInfo, ComparedProduct, Product, ProductComparison

CATEGORY_LABELS = {
    Category.keyboards: "Keyboards",
    Category.mice: "Mice",
    Category.headphones: "Headphones",
    Category.laptop_stands: "Laptop stands",
    Category.monitors: "Monitors",
    Category.webcams: "Webcams",
    Category.desk_accessories: "Desk accessories",
}

# Phrases that imply a category. Multi-word phrases are matched before single words
# (so "monitor arm" -> desk accessories, not monitors).
_CATEGORY_ALIASES: list[tuple[str, Category]] = sorted(
    [
        ("keyboard", Category.keyboards), ("keeb", Category.keyboards),
        ("mouse", Category.mice), ("mice", Category.mice),
        ("headphone", Category.headphones), ("headset", Category.headphones), ("earbud", Category.headphones),
        ("earphone", Category.headphones), ("headphones", Category.headphones),
        ("laptop stand", Category.laptop_stands), ("laptop riser", Category.laptop_stands), ("riser", Category.laptop_stands),
        ("cooling pad", Category.laptop_stands), ("stand", Category.laptop_stands),
        ("monitor", Category.monitors), ("display", Category.monitors), ("screen", Category.monitors),
        ("webcam", Category.webcams), ("camera", Category.webcams), ("web cam", Category.webcams),
        ("monitor arm", Category.desk_accessories), ("desk lamp", Category.desk_accessories), ("lamp", Category.desk_accessories),
        ("desk mat", Category.desk_accessories), ("mat", Category.desk_accessories), ("organizer", Category.desk_accessories),
        ("cable", Category.desk_accessories), ("desk accessory", Category.desk_accessories),
        ("desk accessories", Category.desk_accessories), ("accessory", Category.desk_accessories),
    ],
    key=lambda pair: -len(pair[0]),
)

_STOPWORDS = {
    "a", "an", "the", "for", "with", "and", "or", "of", "to", "in", "on", "my", "me", "i", "need", "want", "find", "show",
    "some", "any", "under", "below", "less", "than", "over", "above", "around", "about", "best", "good", "great", "cheap",
    "cheapest", "affordable", "budget", "price", "usd", "dollar", "dollars", "setup", "home", "please", "that", "is", "are",
}

_SYNONYMS = {
    "noise": "noise-cancelling", "anc": "noise-cancelling", "cancelling": "noise-cancelling", "canceling": "noise-cancelling",
    "bt": "bluetooth", "ergo": "ergonomic", "rgb": "rgb", "silent": "quiet", "4k": "4k", "uhd": "4k",
    "foldable": "foldable", "portable": "portable", "mechanical": "mechanical", "gamer": "gaming",
    "mic": "microphone", "wifi": "wireless", "cordless": "wireless",
}

_PRICE_WORDS = {"cheap", "cheapest", "cheaper", "affordable", "budget", "inexpensive", "value"}


class SortOrder(str, Enum):
    relevance = "relevance"
    price_asc = "price_asc"
    price_desc = "price_desc"
    rating = "rating"


def _singular(word: str) -> str:
    if word == "mice":
        return "mouse"
    if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def _tokens(text: str) -> list[str]:
    return [_singular(t) for t in re.findall(r"[a-z0-9]+(?:-[a-z0-9]+)*", text.lower())]


def parse_category(value: str | Category | None) -> Category | None:
    """Accepts 'keyboards', 'Keyboard', 'laptop stand', 'desk accessories', ... Raises on unknown values."""
    if value is None or isinstance(value, Category):
        return value
    text = value.strip().lower().replace("-", " ").replace("_", " ")
    if not text:
        return None
    for cat in Category:
        if text in (cat.value.replace("_", " "), CATEGORY_LABELS[cat].lower()):
            return cat
    for phrase, cat in _CATEGORY_ALIASES:
        if " ".join(_singular(w) for w in text.split()) == " ".join(_singular(w) for w in phrase.split()):
            return cat
    raise InvalidInputError(
        f"Unknown category '{value}'. Valid categories: {', '.join(c.value for c in Category)}.",
        code="invalid_category",
    )


def _infer_categories(query: str) -> tuple[set[Category], str]:
    """Return categories mentioned in the query and the query with those phrases removed."""
    text = " " + " ".join(_tokens(query)) + " "
    found: set[Category] = set()
    for phrase, cat in _CATEGORY_ALIASES:
        needle = " " + " ".join(_singular(w) for w in phrase.split()) + " "
        if needle in text:
            found.add(cat)
            text = text.replace(needle, " ")
    return found, text


class CatalogService:
    def __init__(self, repository: ProductRepository):
        self.repository = repository

    def get(self, product_id: str) -> Product:
        return self.repository.get(product_id)

    def categories(self) -> list[CategoryInfo]:
        products = self.repository.all()
        return [
            CategoryInfo(id=c, label=CATEGORY_LABELS[c], product_count=sum(1 for p in products if p.category == c))
            for c in Category
        ]

    def search(
        self,
        query: str | None = None,
        category: str | Category | None = None,
        min_price: float | None = None,
        max_price: float | None = None,
        min_rating: float | None = None,
        in_stock_only: bool = False,
        sort: SortOrder = SortOrder.relevance,
        limit: int = 50,
    ) -> list[Product]:
        if min_price is not None and max_price is not None and min_price > max_price:
            raise InvalidInputError("min_price cannot be greater than max_price.")
        limit = max(1, min(limit, 50))

        explicit_category = parse_category(category)
        inferred: set[Category] = set()
        terms: list[str] = []  # all meaningful words (incl. category words, which help rank e.g. "lamp")
        remaining_terms: list[str] = []  # words beyond the category phrase
        price_sensitive = False
        if query:
            inferred, rest = _infer_categories(query)
            all_tokens = _tokens(query)
            price_sensitive = any(t in _PRICE_WORDS for t in all_tokens)
            terms = [_SYNONYMS.get(t, t) for t in all_tokens if t not in _STOPWORDS and not t.isdigit()]
            remaining_terms = [_SYNONYMS.get(t, t) for t in rest.split() if t not in _STOPWORDS and not t.isdigit()]

        allowed = {explicit_category} if explicit_category else inferred

        scored: list[tuple[float, Product]] = []
        for p in self.repository.all():
            if allowed and p.category not in allowed:
                continue
            if min_price is not None and p.price < _dec(min_price):
                continue
            if max_price is not None and p.price > _dec(max_price):
                continue
            if min_rating is not None and p.rating < min_rating:
                continue
            if in_stock_only and not p.available:
                continue
            score = self._score(p, terms)
            # Free-text query with no category hit and no term hit -> not relevant.
            if query and remaining_terms and not inferred and not explicit_category and score == 0:
                continue
            scored.append((score, p))

        if sort == SortOrder.price_asc:
            scored.sort(key=lambda sp: (sp[1].price, -sp[1].rating))
        elif sort == SortOrder.price_desc:
            scored.sort(key=lambda sp: (-sp[1].price, -sp[1].rating))
        elif sort == SortOrder.rating:
            scored.sort(key=lambda sp: (-sp[1].rating, sp[1].price))
        elif price_sensitive:
            scored.sort(key=lambda sp: (-sp[0], sp[1].price, -sp[1].rating))
        else:
            scored.sort(key=lambda sp: (-sp[0], -sp[1].rating, sp[1].price))
        return [p for _, p in scored[:limit]]

    @staticmethod
    def _score(p: Product, terms: list[str]) -> float:
        if not terms:
            return 0.0
        name = set(_tokens(p.name)) | {p.brand.lower()}
        attrs = {_singular(f) for f in p.features + p.tags}
        attr_parts = {part for f in attrs for part in f.split("-")}
        desc = set(_tokens(p.description))
        score = 0.0
        for t in terms:
            if t in attrs:
                score += 3
            elif t in name:
                score += 2.5
            elif t in attr_parts:
                score += 1.5
            elif t in desc:
                score += 1
        return score

    def compare(self, product_ids: list[str]) -> ProductComparison:
        ids = list(dict.fromkeys(i.strip().lower() for i in product_ids if i and i.strip()))
        if not 2 <= len(ids) <= 5:
            raise InvalidInputError("Provide between 2 and 5 distinct product IDs to compare.", code="invalid_comparison")
        products = [self.repository.get(i) for i in ids]

        feature_sets = [set(p.features) for p in products]
        shared = set.intersection(*feature_sets)
        compared = [
            ComparedProduct(
                product=p,
                value_score=round(p.rating / math.sqrt(float(p.price)) * 10, 2),
                unique_features=sorted(set(p.features) - shared),
            )
            for p in products
        ]
        cheapest = min(products, key=lambda p: (p.price, -p.rating))
        top_rated = max(products, key=lambda p: (p.rating, p.review_count))
        best_value = max(compared, key=lambda c: c.value_score).product

        parts = [f"{cheapest.name} is the cheapest at ${cheapest.price}."]
        parts.append(
            f"{top_rated.name} has the highest rating ({top_rated.rating}/5 from {top_rated.review_count} reviews)."
            if top_rated.id != cheapest.id
            else f"It is also the highest rated ({top_rated.rating}/5)."
        )
        if best_value.id not in (cheapest.id, top_rated.id):
            parts.append(f"{best_value.name} offers the best rating for the price.")
        unavailable = [p.name for p in products if not p.available]
        if unavailable:
            parts.append(f"Currently out of stock: {', '.join(unavailable)}.")

        prices = [float(p.price) for p in products]
        return ProductComparison(
            products=compared,
            shared_features=sorted(shared),
            cheapest_id=cheapest.id,
            highest_rated_id=top_rated.id,
            best_value_id=best_value.id,
            price_range=(min(prices), max(prices)),
            summary=" ".join(parts),
        )


def _dec(value: float) -> Decimal:
    return money(value)
