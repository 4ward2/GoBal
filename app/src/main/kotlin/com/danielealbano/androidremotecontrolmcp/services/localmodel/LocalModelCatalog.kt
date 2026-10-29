package com.danielealbano.androidremotecontrolmcp.services.localmodel

import kotlinx.serialization.Serializable

/** One file of a model: its name on the phone, where it is fetched from, and its exact size. */
@Serializable
data class LocalModelFile(
    val name: String,
    val url: String,
    val bytes: Long,
)

/**
 * A model the embedded llama.cpp server can run on the phone. Stored next to its files as
 * `model.json`, so deleting the model's folder forgets it too.
 *
 * @param mmproj the vision projector when the model can look at pictures.
 * @param contextSize the context the server is started with (and the chat side budgets for).
 * @param ramBytes memory the running server needs: weights, projector, context and headroom.
 * @param hub the [ModelHub] it came from, by name; [repo] its repository there.
 */
@Serializable
data class LocalModelSpec(
    val id: String,
    val name: String,
    val summary: String = "",
    val weights: LocalModelFile,
    val mmproj: LocalModelFile? = null,
    val contextSize: Int,
    val ramBytes: Long,
    val hub: String = "",
    val repo: String = "",
) {
    val files: List<LocalModelFile> get() = listOfNotNull(weights, mmproj)
    val downloadBytes: Long get() = files.sumOf { it.bytes }
}

/**
 * Models the app knew before models could be added from a link. A folder left by one of them
 * (files but no `model.json`) is adopted as an added model, so nothing already downloaded is lost.
 *
 * No screenshot-driven phone agents (AutoGLM and the like): the phone is operated through the
 * accessibility tree. Those models were tried and read a single screenshot for minutes on a phone.
 */
object LocalModelCatalog {
    private const val GIB = 1024L * 1024 * 1024

    /** GLM-Edge was trained on 3072 tokens; the server caps any larger setting to that. */
    private const val EDGE_CONTEXT = 3072
    private const val EDGE_RAM = 4 * GIB
    private const val EDGE_WEIGHTS_BYTES = 2_485_562_304

    val GLM_EDGE_4B =
        LocalModelSpec(
            id = "glm-edge-4b-chat",
            name = "GLM-Edge 4B 对话（智谱）",
            weights =
                LocalModelFile(
                    "glm-edge-4b-chat-Q4_0.gguf",
                    ModelHub.HF_MIRROR.fileUrl(ModelHub.HF_MIRROR.exampleRepo, "ggml-model-Q4_0.gguf"),
                    EDGE_WEIGHTS_BYTES,
                ),
            contextSize = EDGE_CONTEXT,
            ramBytes = EDGE_RAM,
            hub = ModelHub.HF_MIRROR.name,
            repo = ModelHub.HF_MIRROR.exampleRepo,
        )

    val LEGACY: List<LocalModelSpec> = listOf(GLM_EDGE_4B)
}
