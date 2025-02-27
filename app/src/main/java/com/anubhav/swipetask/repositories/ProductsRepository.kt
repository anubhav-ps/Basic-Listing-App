package com.anubhav.swipetask.repositories

import android.content.Context
import android.net.Uri
import com.anubhav.swipetask.database.dao.ProductsDao
import com.anubhav.swipetask.models.Product
import com.anubhav.swipetask.repositories.models.DataStatus
import com.anubhav.swipetask.services.models.ProductUploadRequest
import com.anubhav.swipetask.services.models.ProductUploadResponse
import com.anubhav.swipetask.services.services.ProductsService
import com.anubhav.swipetask.utils.getFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ProductsRepository(
    private val productService: ProductsService,
    private val productsDao: ProductsDao,
    private val context: Context
) {

    private val TAG = "Products-Repository"
    val allProducts: Flow<MutableList<Product>> = productsDao.getAllProducts()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    suspend fun pullProductsFromServer() = flow {
        emit(DataStatus.loading())
        val networkResult = productService.getProducts()
        when (networkResult.code()) {
            200 -> {
                val productListFromNetwork = networkResult.body() ?: emptyList()
                if (productListFromNetwork.isNotEmpty()) {
                    emit(DataStatus.success(productListFromNetwork))
                }
            }

            400, 500 -> emit(DataStatus.failed(networkResult.message().toString()))
        }
    }.catch { emit(DataStatus.failed(it.message.toString())) }
        .flowOn(Dispatchers.IO)

    suspend fun storeProduct(products: List<Product>) {
        productsDao.insertProduct(products)
    }

    suspend fun searchProduct(query: String) = flow {
        emit(DataStatus.loading())
        productsDao.searchForProductName(query).collect {
            emit(DataStatus.success(it))
        }
    }.catch { emit(DataStatus.failed(it.message.toString())) }.flowOn(Dispatchers.IO)

    fun postProduct(
        product: Product,
        uri: Uri?
    ) = flow {
        emit(DataStatus.loading())
        val imageFile = if (uri != null) {
            //copying file from external storage to app cache
            val parcelFileDescriptor =
                context.contentResolver.openFileDescriptor(uri, "r", null)
            val file = File(context.cacheDir, context.contentResolver.getFileName(uri))
            val inputStream = FileInputStream(parcelFileDescriptor?.fileDescriptor)
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            MultipartBody.Part.createFormData("file", "file", ProductUploadRequest(file, "image"))
        } else {
            null
        }
        val response = productService.postProductWithImage(
            product.productName.toRequestBody("multipart/form-data".toMediaTypeOrNull()),
            product.productType.toRequestBody("multipart/form-data".toMediaTypeOrNull()),
            product.price.toString()
                .toRequestBody("multipart/form-data".toMediaTypeOrNull()),
            product.tax.toString()
                .toRequestBody("multipart/form-data".toMediaTypeOrNull()),
            imageFile
        )
        when (response.code()) {
            200 -> {
                response.body()?.apply {
                    emit(DataStatus.success(this))
                }
            }

            400, 500 -> {
                response.body()?.apply {
                    emit(DataStatus.failed<ProductUploadResponse>(this.message))
                }
            }
        }
    }.catch { emit(DataStatus.failed(it.message.toString())) }
        .flowOn(Dispatchers.IO)

}