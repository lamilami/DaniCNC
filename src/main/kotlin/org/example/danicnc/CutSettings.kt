package org.example.danicnc

enum class CutType(private val label: String) {
    ON_PATH("On Path"), INSIDE("Inside"), OUTSIDE("Outside"), POCKET("Pocket");
    override fun toString() = label
}

/** Project state only; no toolpaths or changes to contour geometry. */
data class CutSettings(
    val type: CutType = CutType.ON_PATH,
    val depthMm: Double = 1.0,
    val bitMm: Double = 3.175,
    val feedMmMin: Double = 600.0,
    val plungeMmMin: Double = 200.0,
    val passDepthMm: Double = 1.0,
    val spindleRpm: Int = 12000,
    val stepOverPercent: Double = 40.0,
) {
    init {
        require(stepOverPercent.isFinite() && stepOverPercent in 1.0..50.0) { "Pocket Step Over must be between 1 and 50%." }
        require(listOf(depthMm, feedMmMin, plungeMmMin, passDepthMm).all { it.isFinite() && it > 0 }) {
            "Depth, feed, plunge and depth per pass must be positive finite numbers."
        }
        require(bitMm in BITS) { "Choose a supported bit diameter." }
        require(spindleRpm > 0) { "Spindle speed must be a positive whole number." }
    }

    companion object {
        val BITS = listOf(1.0, 2.0, 3.175, 6.0)

        /** Validate the whole selection before committing any change. */
        fun applyTo(contours: List<Contour>, update: (CutSettings) -> CutSettings) {
            val changes = contours.map { contour ->
                val settings = update(contour.cutSettings)
                require(contour.closed || settings.type == CutType.ON_PATH) {
                    "Open contours support On Path only."
                }
                contour to settings
            }
            changes.forEach { (contour, settings) -> contour.cutSettings = settings }
        }
    }
}
