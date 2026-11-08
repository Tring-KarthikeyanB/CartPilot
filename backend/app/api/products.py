from fastapi import APIRouter, Depends, Query, Request

from app.api.deps import absolute_url, get_catalog_service
from app.schemas.product import CategoryInfo, ComparedProduct, Product, ProductComparison, ProductListResponse
from app.services.products.catalog_service import CatalogService, SortOrder

router = APIRouter(prefix="/products", tags=["products"])


def present(product: Product, request: Request) -> Product:
    return product.model_copy(update={"image_url": absolute_url(request, product.image_url)})


@router.get("", response_model=ProductListResponse)
def list_products(
    request: Request,
    q: str | None = Query(default=None, max_length=200, description="Free-text search, e.g. 'wireless keyboard'"),
    category: str | None = Query(default=None, description="Category id, e.g. keyboards, laptop_stands"),
    min_price: float | None = Query(default=None, ge=0),
    max_price: float | None = Query(default=None, ge=0),
    min_rating: float | None = Query(default=None, ge=0, le=5),
    in_stock: bool = Query(default=False, description="Only products that can be purchased now"),
    sort: SortOrder = SortOrder.relevance,
    limit: int = Query(default=50, ge=1, le=50),
    catalog: CatalogService = Depends(get_catalog_service),
) -> ProductListResponse:
    items = catalog.search(
        query=q, category=category, min_price=min_price, max_price=max_price,
        min_rating=min_rating, in_stock_only=in_stock, sort=sort, limit=limit,
    )
    return ProductListResponse(items=[present(p, request) for p in items], total=len(items))


@router.get("/categories", response_model=list[CategoryInfo])
def list_categories(catalog: CatalogService = Depends(get_catalog_service)) -> list[CategoryInfo]:
    return catalog.categories()


@router.get("/compare", response_model=ProductComparison)
def compare_products(
    request: Request,
    ids: str = Query(description="Comma-separated product IDs (2-5), e.g. kb-001,kb-003"),
    catalog: CatalogService = Depends(get_catalog_service),
) -> ProductComparison:
    result = catalog.compare(ids.split(","))
    return result.model_copy(
        update={
            "products": [
                ComparedProduct(product=present(c.product, request), value_score=c.value_score, unique_features=c.unique_features)
                for c in result.products
            ]
        }
    )


@router.get("/{product_id}", response_model=Product)
def get_product(product_id: str, request: Request, catalog: CatalogService = Depends(get_catalog_service)) -> Product:
    return present(catalog.get(product_id), request)
