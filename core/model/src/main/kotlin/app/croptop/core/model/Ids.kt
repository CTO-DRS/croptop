package app.croptop.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class DocumentId(val value: Long)

@Serializable
@JvmInline
value class LayerId(val value: Long)
