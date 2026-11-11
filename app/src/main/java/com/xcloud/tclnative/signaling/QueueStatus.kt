package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.i18n.UiText

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
            estimate == null -> UiText.text("Estimativa do Xbox: indisponível no momento.")
            estimate.seconds < 60 -> UiText.text("Estimativa do Xbox: menos de 1 minuto.")
            else -> UiText.text("Estimativa do Xbox: cerca de {0}.",UiText.nested(duration(estimate.seconds)))
        }
        return buildString {
            appendLine(prediction)
            appendLine(UiText.text("Você já aguardou: {0}.",UiText.nested(duration(elapsed))))
            if (stateAge >= 10) appendLine(UiText.text("Consultando o Xbox; última confirmação da fila há {0}.",UiText.nested(duration(stateAge))))
            else appendLine(UiText.text("Xbox confirmou que a sessão está na fila há {0}s.",UiText.nested(stateAge)))
            if (estimateAge != null) appendLine(UiText.join(UiText.text("Previsão consultada há {0}",UiText.nested(duration(estimateAge))),if (estimateAge > 90 || estimateFailed) " (desatualizada)." else "."))
            if (estimateFailed) appendLine(UiText.text("Não foi possível atualizar a previsão; a consulta da vaga continua."))
            appendLine(UiText.text("A previsão pode mudar; não é garantia de horário."))
            append(UiText.text("Limite desta tentativa: {0}. Voltar ou Cancelar sai da fila.",UiText.nested(duration(limitMs / 1000))))
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
