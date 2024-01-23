package ru.netology.nmedia.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.netology.nmedia.dto.AppDb
import ru.netology.nmedia.dto.Post
import ru.netology.nmedia.model.FeedModel
import ru.netology.nmedia.model.FeedModelState
import ru.netology.nmedia.model.SingleLiveEvent
import ru.netology.nmedia.recyclerview.OnInteractionListener
import ru.netology.nmedia.repository.PostRepository
import ru.netology.nmedia.repository.PostRepositoryImpl

private val empty = Post(
    id = 0,
    content = "",
    author = "",
    authorAvatar = "",
    likedByMe = false,
    likes = 0,
    published = ""
)

class PostViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PostRepository =
        PostRepositoryImpl(AppDb.getInstance(context = application).postDao())

    val data: LiveData<FeedModel> = repository.data
        .map(::FeedModel)
        .catch { it.printStackTrace() }
        .asLiveData(Dispatchers.Default)

    private val edited = MutableLiveData(empty)
    private val _postCreated = SingleLiveEvent<Unit>()
    val postCreated: LiveData<Unit>
        get() = _postCreated

    private val _dataState = MutableLiveData<FeedModelState>()
    val dataState: LiveData<FeedModelState>
        get() = _dataState


    private var interactionListener: OnInteractionListener? = null

    // Обработка ошибок на сервере, механизм Event
    private val _errorMessages = MutableLiveData<String?>()
    val errorMessages: LiveData<String?>
        get() = _errorMessages

    // Статус загрузки в ожидании ответа от сервера
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    val newerCount = data.switchMap {
        repository.getNewer(it.posts.firstOrNull()?.id ?: 0L)
            .catch { _dataState.postValue(FeedModelState(error = true)) }
            .asLiveData(Dispatchers.Default, 100)
    }

    init {
        loadPosts()
    }

    // Функции для установки обработчика взаимодействий и переменная для хранения
    fun setInteractionListener(listener: OnInteractionListener) {
        this.interactionListener = listener
    }

    fun getInteractionListener(): OnInteractionListener? {
        return interactionListener
    }

    fun loadPosts() = viewModelScope.launch {
        try {
            _dataState.value = FeedModelState(loading = true)
            repository.getAll()
            _dataState.value = FeedModelState()
        } catch (e: Exception) {
            e.printStackTrace()
            _dataState.value = FeedModelState(error = true)
        }
    }

    private fun refreshPosts() = viewModelScope.launch {
        try {
            _dataState.value = FeedModelState(refreshing = true)
            repository.getAll()
            _dataState.value = FeedModelState()
        } catch (e: Exception) {
            _dataState.value = FeedModelState(error = true)
        }
    }

    fun like(id: Long) = viewModelScope.launch {
        // Получаем текущий список постово
        val currentPosts = data.value?.posts.orEmpty()

        // Получаем текущий пост
        val currentPost = currentPosts.find { it.id == id }

        currentPost?.let { post ->
            // Оптимистичное обновление UI
            val updatedPost = if (post.likedByMe) {
                post.copy(likes = post.likes - 1, likedByMe = false)
            } else {
                post.copy(likes = post.likes + 1, likedByMe = true)
            }

            // Оптимистично обновляем список постов
            val updatedPosts = currentPosts.map { post ->
                if (post.id == id) updatedPost else post
            }
            data.value?.posts = updatedPosts

            // Асинхронное обновление на сервере
            try {
                if (post.likedByMe) {
                    repository.unlikePost(id)
                } else {
                    repository.likePost(id)
                }
            } catch (e: Throwable) {
                // Если запрос не удался, откатываем изменения в UI
                data.value?.posts = currentPosts
                val errorMessage = "Ошибка при нажатии кнопки \"Лайк\". Повторите снова"
                _errorMessages.postValue(errorMessage)
            }
        }
    }

    fun share(id: Long) {}

    fun removeById(id: Long) = viewModelScope.launch {
        // Оптимистичная модель: предполагаем, что пост удален
        val oldPosts = data.value?.posts.orEmpty()
        val updatedPosts = oldPosts.filter { it.id != id }
        data.value?.posts = updatedPosts

        updatedPosts.let {
            try {
                repository.removeById(id)
            } catch (e: Exception) {
                // Если запрос не удался, откатываем изменения в UI
                data.value?.posts = oldPosts
                val errorMessage = "Ошибка при удалении. Повторите снова"
                _errorMessages.postValue(errorMessage)
            }
        }
    }

    fun addNewPost(content: String) = viewModelScope.launch {
        // Показываем индикатор загрузки
        _isLoading.value = true

        val newPost = empty.copy(content = content.trim(), id = 0L)

        // Оптимистичное обновление: добавляем новый пост в UI
        val currentPosts = data.value?.posts.orEmpty()

        data.value?.copy(posts = listOf(newPost) + currentPosts)

        // Ассинхронное сохранение поста
        try {
            repository.save(newPost)
            val updatedPosts = listOf(newPost) + currentPosts.filter { it.id != 0L }
            data.value?.copy(posts = updatedPosts)

            refreshPosts()

            // оповещение об успешном создании
            _postCreated.postValue(Unit)
            resetEditingState()

            // Скрываем индикатор загрузки
            _isLoading.postValue(false)
        } catch (e: Exception) {
            // Если запрос не удался, откатываем изменения в UI
            data.value?.copy(posts = currentPosts)
            _isLoading.postValue(false)
            _errorMessages.postValue("Повторите попытку")
        }
    }

    fun updatePost(postId: Long, content: String) = viewModelScope.launch {
        // Показываем индикатор ожидания ответа от сервера
        _isLoading.value = true

        try {
            // Получаем текущий пост и обновляем его содержимое
            val originalPost = repository.getById(postId)
            val updatedPost = originalPost.copy(content = content.trim())

            // Асинхронно сохраняем обновленный пост
            repository.save(updatedPost)

            // Обновляем список постов в LiveData
            val updatedPosts = data.value?.posts?.map { post ->
                if (post.id == updatedPost.id) updatedPost else post
            }.orEmpty()
            data.value?.copy(posts = updatedPosts)

            // Оповещаем о завершении обновления
            _postCreated.postValue(Unit)
            _isLoading.postValue(false)
        } catch (e: Exception) {
            // Обработка ошибок при обновлении
            _errorMessages.postValue("Повторите попытку")
        } finally {
            // Скрываем индикатор ожидания ответа от сервера
            _isLoading.postValue(false)
        }

        // Сбрасываем состояние редактирования
        resetEditingState()
    }

    // Метод для сбрасывания значения об ошибке при прослушивании событий нажатия лайка
    fun clearErrorMessage() {
        _errorMessages.value = null
    }

    private fun resetEditingState() {
        edited.postValue(empty)
    }

    // Метод для оьновления постов
    fun onFreshPostsClicked() = viewModelScope.launch {
        repository.showNewPosts()

        // Обновление данных для UI
        refreshPosts()
    }
}




