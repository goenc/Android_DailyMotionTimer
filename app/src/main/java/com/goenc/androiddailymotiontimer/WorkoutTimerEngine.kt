package com.goenc.androiddailymotiontimer

class WorkoutTimerEngine(
    val selectedSeconds: Int,
) {
    val totalDurationMs: Long = selectedSeconds * 1_000L
    val boundaryCount: Int = selectedSeconds

    fun initialDisplayValue(): Int = selectedSeconds

    fun boundaryElapsedMs(boundaryIndex: Int): Long {
        require(boundaryIndex in 1..boundaryCount)
        return (totalDurationMs * boundaryIndex) / (selectedSeconds + 1L)
    }

    fun displayValueForBoundary(boundaryIndex: Int): Int {
        require(boundaryIndex in 1..boundaryCount)
        return selectedSeconds - boundaryIndex
    }
}
