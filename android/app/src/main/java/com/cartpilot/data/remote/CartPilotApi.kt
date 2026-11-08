package com.cartpilot.data.remote

import com.cartpilot.data.model.AddItemRequest
import com.cartpilot.data.model.BudgetCheckDto
import com.cartpilot.data.model.CaptureOrderRequestDto
import com.cartpilot.data.model.CartDto
import com.cartpilot.data.model.CategoryDto
import com.cartpilot.data.model.ChatRequestDto
import com.cartpilot.data.model.ChatResponseDto
import com.cartpilot.data.model.ComparisonDto
import com.cartpilot.data.model.CreateOrderRequestDto
import com.cartpilot.data.model.CreateOrderResponseDto
import com.cartpilot.data.model.HealthDto
import com.cartpilot.data.model.OrderStatusDto
import com.cartpilot.data.model.ProductDto
import com.cartpilot.data.model.ProductListDto
import com.cartpilot.data.model.ReceiptDto
import com.cartpilot.data.model.ReplaceItemRequest
import com.cartpilot.data.model.SetBudgetRequest
import com.cartpilot.data.model.UpdateQuantityRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit definition of the CartPilot FastAPI backend (paths are relative to BuildConfig.API_BASE_URL). */
interface CartPilotApi {
    @GET("api/v1/health")
    suspend fun health(): HealthDto

    // ---------- products ----------

    @GET("api/v1/products")
    suspend fun products(
        @Query("q") query: String? = null,
        @Query("category") category: String? = null,
        @Query("max_price") maxPrice: Double? = null,
        @Query("sort") sort: String? = null,
        @Query("limit") limit: Int = 50,
    ): ProductListDto

    @GET("api/v1/products/categories")
    suspend fun categories(): List<CategoryDto>

    @GET("api/v1/products/compare")
    suspend fun compare(@Query("ids") ids: String): ComparisonDto

    @GET("api/v1/products/{id}")
    suspend fun product(@Path("id") id: String): ProductDto

    // ---------- cart ----------

    @GET("api/v1/cart/{sid}")
    suspend fun cart(@Path("sid") sessionId: String): CartDto

    @POST("api/v1/cart/{sid}/items")
    suspend fun addItem(@Path("sid") sessionId: String, @Body body: AddItemRequest): CartDto

    @PATCH("api/v1/cart/{sid}/items/{pid}")
    suspend fun setQuantity(@Path("sid") sessionId: String, @Path("pid") productId: String, @Body body: UpdateQuantityRequest): CartDto

    @DELETE("api/v1/cart/{sid}/items/{pid}")
    suspend fun removeItem(@Path("sid") sessionId: String, @Path("pid") productId: String): CartDto

    @POST("api/v1/cart/{sid}/replace")
    suspend fun replaceItem(@Path("sid") sessionId: String, @Body body: ReplaceItemRequest): CartDto

    @PUT("api/v1/cart/{sid}/budget")
    suspend fun setBudget(@Path("sid") sessionId: String, @Body body: SetBudgetRequest): CartDto

    @GET("api/v1/cart/{sid}/budget-check")
    suspend fun budgetCheck(@Path("sid") sessionId: String): BudgetCheckDto

    // ---------- agent ----------

    @POST("api/v1/agent/chat")
    suspend fun chat(@Body body: ChatRequestDto): ChatResponseDto

    @DELETE("api/v1/agent/sessions/{sid}")
    suspend fun resetConversation(@Path("sid") sessionId: String): Response<Unit>

    // ---------- payments ----------

    @POST("api/v1/payments/create-order")
    suspend fun createOrder(@Body body: CreateOrderRequestDto): CreateOrderResponseDto

    @POST("api/v1/payments/capture-order")
    suspend fun captureOrder(@Body body: CaptureOrderRequestDto): ReceiptDto

    @GET("api/v1/payments/orders/{oid}")
    suspend fun orderStatus(@Path("oid") orderId: String, @Query("session_id") sessionId: String): OrderStatusDto
}
