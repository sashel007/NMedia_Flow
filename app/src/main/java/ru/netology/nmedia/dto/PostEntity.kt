package ru.netology.nmedia.dto

import androidx.room.Entity
import androidx.room.PrimaryKey

fun List<PostEntity>.toDto(): List<Post> = map(PostEntity::toDto)
fun List<Post>.toEntity(): List<PostEntity> = map(PostEntity.Companion::toEntity)

@Entity
data class PostEntity(
    @PrimaryKey(autoGenerate = true) var id: Long,
    val author: String,
    val authorAvatar: String,
    val content: String,
    val published: String,
    val likedByMe: Boolean,
    val likes: Int = 0,
    val shouldBeDisplayed: Boolean = true
//    var sharings: Int,
//    var video: String?
) {
    fun toDto() = Post(id, author, authorAvatar, content, published, likedByMe, likes)

    companion object {
        fun toEntity(post: Post) = PostEntity(
            post.id,
            post.author,
            post.authorAvatar,
            post.content,
            post.published,
            post.likedByMe,
            post.likes
        )
    }
}