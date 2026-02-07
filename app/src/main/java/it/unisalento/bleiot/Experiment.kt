package it.unisalento.bleiot

import kotlinx.serialization.Serializable

@Serializable
data class Experiment(
    val id: String,
    val description: String
)
