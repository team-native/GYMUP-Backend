package com.teamnative.gymup.api

import com.teamnative.gymup.core.JwtAuthenticationInterceptor
import com.teamnative.gymup.db.DomainStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.math.roundToInt

@RestController
@RequestMapping("/api/v1")
class GuideAndToolsController(
    private val domainStore: DomainStore,
) {
    @GetMapping("/exercises/{exerciseId}/guide")
    fun exerciseGuide(@PathVariable exerciseId: String, request: HttpServletRequest): Nothing {
        userId(request)
        throw ApiException(HttpStatus.NOT_FOUND, "GUIDE_NOT_FOUND", "No guide is configured for this exercise")
    }

    @GetMapping("/exercises/{exerciseId}/first-time-status")
    fun firstTimeStatus(@PathVariable exerciseId: String, request: HttpServletRequest): Map<String, Any?> {
        val userId = userId(request)
        val hasHistory = domainStore.list(userId, "workout").any { workout ->
            workout.payload["status"] == "COMPLETED" &&
                workout.payload.objectList("sets").any { it["exerciseId"].toString() == exerciseId }
        }
        return mapOf("exerciseId" to exerciseId, "isFirstTime" to !hasHistory, "guideAvailable" to false)
    }

    @PostMapping("/tools/plate-calculator")
    fun plateCalculator(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        userId(request)
        for (key in listOf("targetWeight", "barWeight", "availablePlates")) {
            if (!body.containsKey(key)) {
                throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEIGHT", "$key is required")
            }
        }
        val targetWeight = body.double("targetWeight")
        val barWeight = body.double("barWeight")
        val eachSideTarget = ((targetWeight - barWeight) / 2.0 * 1000).roundToInt()
        if (eachSideTarget < 0) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEIGHT", "targetWeight must be at least barWeight")
        }
        val plates = (body["availablePlates"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() }
            ?.filter { it > 0 }
            ?.sortedDescending()
            ?: emptyList()
        val weights = plates.map { (it * 1000).roundToInt() }
        val best = arrayOfNulls<List<Int>>(eachSideTarget + 1)
        best[0] = emptyList()
        for (amount in 0..eachSideTarget) {
            val current = best[amount] ?: continue
            weights.forEachIndexed { index, weight ->
                val total = amount + weight
                if (total <= eachSideTarget) {
                    val candidate = current + index
                    val previous = best[total]
                    if (previous == null || candidate.size < previous.size) best[total] = candidate
                }
            }
        }
        val actual = (eachSideTarget downTo 0).firstOrNull { best[it] != null } ?: 0
        val counts = linkedMapOf<Double, Int>()
        best[actual].orEmpty().forEach { index -> counts[plates[index]] = (counts[plates[index]] ?: 0) + 1 }
        return mapOf(
            "achievable" to (actual == eachSideTarget),
            "eachSide" to counts.map { (weight, count) -> mapOf("weight" to weight, "count" to count) },
            "actualWeight" to barWeight + 2 * actual / 1000.0,
            "remainder" to 2 * (eachSideTarget - actual) / 1000.0,
        )
    }

    private fun userId(request: HttpServletRequest): String =
        request.getAttribute(JwtAuthenticationInterceptor.USER_ID_ATTRIBUTE) as? String
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bearer token is required")

    private fun Map<String, Any?>.double(key: String): Double =
        (this[key] as? Number)?.toDouble() ?: this[key]?.toString()?.toDoubleOrNull()
        ?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEIGHT", "$key must be a number")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
}
