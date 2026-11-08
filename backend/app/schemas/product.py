from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_serializer


class Category(str, Enum):
    keyboards = "keyboards"
    mice = "mice"
    headphones = "headphones"
    laptop_stands = "laptop_stands"
    monitors = "monitors"
    webcams = "webcams"
    desk_accessories = "desk_accessories"


class StockStatus(str, Enum):
    in_stock = "in_stock"
    low_stock = "low_stock"
    out_of_stock = "out_of_stock"


class Product(BaseModel):
    """A catalogue product. `image_url` is a server path in the seed file and absolute in API responses."""

    model_config = ConfigDict(frozen=True)

    id: str = Field(pattern=r"^[a-z]{2}-\d{3}$")
    name: str
    brand: str
    category: Category
    description: str
    price: Decimal = Field(gt=0, decimal_places=2)
    currency: str = Field(pattern=r"^USD$")
    rating: float = Field(ge=0, le=5)
    review_count: int = Field(ge=0)
    image_url: str
    stock_status: StockStatus
    features: list[str] = []
    tags: list[str] = []

    @property
    def available(self) -> bool:
        return self.stock_status != StockStatus.out_of_stock

    @field_serializer("price")
    def _price_as_number(self, v: Decimal) -> float:
        return float(v)


class ProductListResponse(BaseModel):
    items: list[Product]
    total: int


class CategoryInfo(BaseModel):
    id: Category
    label: str
    product_count: int


class ComparedProduct(BaseModel):
    product: Product
    value_score: float
    unique_features: list[str]


class ProductComparison(BaseModel):
    products: list[ComparedProduct]
    shared_features: list[str]
    cheapest_id: str
    highest_rated_id: str
    best_value_id: str
    price_range: tuple[float, float]
    summary: str
