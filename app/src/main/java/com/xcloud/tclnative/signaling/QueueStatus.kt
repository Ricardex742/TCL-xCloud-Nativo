package com.xcloud.tclnative.signaling

import org.json.JSONObject
import kotlin.math.ceil

/** Service estimates are per title, not a promise or a user's position in the queue. */
data class QueueEstimate(val seconds: Long) {
    companion object {
        fun parse(body: String): QueueEstimate? {
            val value = JSONObject(body).opt("estimatedTotalWaitTimeInSeconds") as? Number ?: return null
            val seconds = value.toDouble()
            if (!seconds.isFinite() || seconds < 0 || seconds > 7 * 24 * 3600) return null
            return QueueEstimate(ceil(seconds).toLong())
        }
    }
}

data class QueueStatus(
    val enteredAtMs: Long,
    val confirmedAtMs: Long,
    val estimate: QueueEstimate? = null,
    val estimateAtMs: Long? = null,
    val estimateFailed: Boolean = false,
    val limitMs: Long = 60 * 60_000
) {
    fun message(nowMs: Long): String {
        val elapsed = ((nowMs - enteredAtMs).coerceAtLeast(0) / 1000)
        val stateAge = ((nowMs - confirmedAtMs).coerceAtLeast(0) / 1000)
        val estimateAge = estimateAtMs?.let { ((nowMs - it).coerceAtLeast(0) / 1000) }
        val prediction = when {
            estimate == null -> "Estimativa do Xbox: indisponível no momento."
            estimate.seconds < 60 -> "Estimativa do Xbox: menos de 1 minuto."
            else -> "Estimativa do Xbox: cerca de ${duration(estimate.seconds)}."
        }
        return buildString {
            appendLine(prediction)
            appendLine("Você já aguardou: ${duration(elapsed)}.")
            if (stateAge >= 10) appendLine("Consultando o Xbox; última confirmação da fila há ${duration(stateAge)}.")
            else appendLine("Xbox confirmou que a sessão está na fila há ${stateAge}s.")
            if (estimateAge != null) appendLine("Previsão consultada há ${duration(estimateAge)}" +
                if (estimateAge > 90 || estimateFailed) " (desatualizada)." else ".")
            if (estimateFailed) appendLine("Não foi possível atualizar a previsão; a consulta da vaga continua.")
            appendLine("A previsão pode mudar; não é garantia de horário.")
            append("Limite desta tentativa: ${duration(limitMs / 1000)}. Voltar ou Cancelar sai da fila.")
        }
    }

    companion object {
        fun duration(seconds: Long): String {
            val value = seconds.coerceAtLeast(0)
            val minutes = value / 60
            val rest = value % 60
            return when {
                minutes == 0L -> "${rest}s"
                rest == 0L -> "$minutes min"
                else -> "$minutes min ${rest}s"
            }
        }
    }
}
