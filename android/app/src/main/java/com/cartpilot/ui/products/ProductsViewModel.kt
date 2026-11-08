package com.cartpilot.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cartpilot.core.AppContainer
import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.Category
import com.cartpilot.domain.model.Comparison
import com.cartpilot.domain.model.Product
import com.cartpilot.domain.repository.CartRepository
import com.cartpilot.domain.repository.ProductRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_COMPARE = 3

data class ProductsUiState(
    val query: String = "",
    val category: String? = null,
    val categories: List<Category> = emptyList(),
    val products: List<Product> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val compareMode: Boolean = false,
    val compareIds: List<String> = emptyList(),
    val comparison: Comparison? = null,
    val comparing: Boolean = false,
    val selectedProduct: Product? = null,
    val addingId: String? = null,
    val notice: String? = null,
    val cartCount: Int = 0,
)

/** User intents of the products screen (implemented by [ProductsViewModel]; no-op fakes in previews/screenshots). */
interface ProductsActions {
    fun onQueryChange(query: String)
    fun onCategory(category: String?)
    fun retry()
    fun toggleCompareMode()
    fun toggleCompare(product: Product)
    fun compare()
    fun dismissComparison()
    fun selectProduct(product: Product?)
    fun addToCart(product: Product)
}

class ProductsViewModel(
    initialCategory: String?,
    private val products: ProductRepository,
    private val carts: CartRepository,
) : ViewModel(), ProductsActions {

    private val _state = MutableStateFlow(ProductsUiState(category = initialCategory))
    val state: StateFlow<ProductsUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            (products.categories() as? AppResult.Success)?.let { r -> _state.update { it.copy(categories = r.data) } }
        }
        viewModelScope.launch { carts.cart.collect { c -> _state.update { it.copy(cartCount = c?.itemCount ?: 0) } } }
        search(debounce = false)
    }

    override fun onQueryChange(query: String) {
        _state.update { it.copy(query = query.take(100)) }
        search(debounce = true)
    }

    override fun onCategory(category: String?) {
        _state.update { it.copy(category = if (it.category == category) null else category) }
        search(debounce = false)
    }

    override fun retry() = search(debounce = false)

    private fun search(debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            when (val result = products.search(s.query, s.category)) {
                is AppResult.Success -> _state.update { it.copy(products = result.data, loading = false) }
                is AppResult.Failure -> _state.update { it.copy(error = result.message, loading = false) }
            }
        }
    }

    // ---------- compare ----------

    override fun toggleCompareMode() = _state.update { it.copy(compareMode = !it.compareMode, compareIds = emptyList()) }

    override fun toggleCompare(product: Product) = _state.update {
        val ids = when {
            product.id in it.compareIds -> it.compareIds - product.id
            it.compareIds.size >= MAX_COMPARE -> return@update it.copy(notice = "You can compare up to $MAX_COMPARE products")
            else -> it.compareIds + product.id
        }
        it.copy(compareIds = ids)
    }

    override fun compare() {
        val ids = _state.value.compareIds
        if (ids.size < 2) return
        _state.update { it.copy(comparing = true) }
        viewModelScope.launch {
            when (val result = products.compare(ids)) {
                is AppResult.Success -> _state.update { it.copy(comparison = result.data, comparing = false) }
                is AppResult.Failure -> _state.update { it.copy(comparing = false, notice = result.message) }
            }
        }
    }

    override fun dismissComparison() = _state.update { it.copy(comparison = null) }

    // ---------- cart ----------

    override fun selectProduct(product: Product?) = _state.update { it.copy(selectedProduct = product) }

    override fun addToCart(product: Product) {
        _state.update { it.copy(addingId = product.id) }
        viewModelScope.launch {
            val result = carts.add(product.id)
            _state.update {
                when (result) {
                    is AppResult.Success -> it.copy(addingId = null, notice = "Added ${product.name}", selectedProduct = null)
                    is AppResult.Failure -> it.copy(addingId = null, notice = result.message)
                }
            }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    companion object {
        fun factory(initialCategory: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProductsViewModel(initialCategory, AppContainer.productRepository, AppContainer.cartRepository) }
        }
    }
}
