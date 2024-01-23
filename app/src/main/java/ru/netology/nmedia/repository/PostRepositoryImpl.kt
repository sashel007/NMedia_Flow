package ru.netology.nmedia.repository

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import retrofit2.Response
import ru.netology.nmedia.api.PostsApi
import ru.netology.nmedia.dto.PostDao
import ru.netology.nmedia.dto.Post
import ru.netology.nmedia.dto.PostEntity
import ru.netology.nmedia.dto.toDto
import java.io.IOException
import java.sql.SQLException

class PostRepositoryImpl(private val dao: PostDao) : PostRepository {

    override val data: Flow<List<Post>> = dao.getAll()
        .map(List<PostEntity>::toDto)

    override fun getNewer(id: Long): Flow<Int> = flow {
        while(true) {
            delay(10_000L)
            val response = PostsApi.retrofitService.getNewer(id)
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(response.code(), response.message())

            // Преобразовать список постов в Entity
            val postsToInsert = body.map {
                PostEntity.toEntity(it).copy(shouldBeDisplayed = false)
            }
            dao.insert(postsToInsert)
            emit(body.size)
        }
    }.catch { e -> throw AppError.from(e) }

    override suspend fun getAll(): List<Post> {
        try {
            val response = PostsApi.retrofitService.getAll()
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(response.code(), response.message())

            // Преобразование List<Post> в List<PostEntity>
            val postListEntity = body.map { post ->
                PostEntity.toEntity(post)
            }
            // Вставка списка объектов сущности в базу данных
            dao.insert(postListEntity)

            return body
        } catch (e: IOException) {
            Log.e("PostRepository", "Network error", e)
            throw NetworkError
        } catch (e: Exception) {
            Log.e("PostRepository", "Unknown error", e)
            throw UnknownError
        }
    }

    override suspend fun getById(id: Long): Post {
        try {
            val response = PostsApi.retrofitService.getById(id)
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val post: Post = response.body() ?: throw ApiError(response.code(), response.message())
            val postEntity: PostEntity = PostEntity.toEntity(post)
            dao.insert(postEntity)
            return post
        } catch (e: IOException) {
            throw NetworkError
        } catch (e: Exception) {
            throw UnknownError
        }
    }

    override suspend fun likePost(id: Long): Post {
        try {
            val response: Response<Post> = PostsApi.retrofitService.likePost(id)
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val likedPost: Post =
                response.body() ?: throw ApiError(response.code(), response.message())
            val likedPostEntity = PostEntity.toEntity(likedPost)
            dao.like(likedPostEntity.id)
            return likedPost
        } catch (e: IOException) {
            throw NetworkError
        } catch (e: Exception) {
            throw UnknownError
        }
    }

    override suspend fun unlikePost(id: Long): Post {
        try {
            val response: Response<Post> = PostsApi.retrofitService.likePost(id)
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val likedPost: Post =
                response.body() ?: throw ApiError(response.code(), response.message())
            val likedPostEntity = PostEntity.toEntity(likedPost)
            dao.like(likedPostEntity.id)
            return likedPost
        } catch (e: IOException) {
            throw NetworkError
        } catch (e: Exception) {
            throw UnknownError
        }
    }

    override suspend fun removeById(id: Long) {
        try {
            val response = PostsApi.retrofitService.removeById(id)
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            dao.removeById(id)
        } catch (e: IOException) {
            throw NetworkError
        } catch (e: Exception) {
            throw UnknownError
        }
    }


    override suspend fun save(post: Post): Post {
        try {
            val response = PostsApi.retrofitService.save(post)
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val post = response.body() ?: throw ApiError(response.code(), response.message())
            val postEntity = PostEntity.toEntity(post)
            dao.save(postEntity)
            return post
        } catch (e: IOException) {
            throw NetworkError
        } catch (e: Exception) {
            throw UnknownError
        }
    }

    override suspend fun showNewPosts() {
        Log.d("PostRepository", "Updating posts to be displayed")
        dao.showNewPosts()
        Log.d("PostRepository", "Posts updated")
    }


}

sealed class AppError(var code: String) : java.lang.RuntimeException() {
    companion object {
        fun from(e: Throwable): AppError = when (e) {
            is AppError -> e
            is SQLException -> DbError
            is IOException -> NetworkError
            else -> UnknownError
        }
    }
}

class ApiError(val status: Int, code: String) : AppError(code)
data object NetworkError : AppError(code = "error_network")
data object DbError : AppError("error_db")
data object UnknownError : AppError(code = "error_unknown")
