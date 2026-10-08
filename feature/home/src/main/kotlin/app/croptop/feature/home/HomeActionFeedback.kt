package app.croptop.feature.home

enum class HomeActionPhase { ACKNOWLEDGED, RUNNING, COMPLETED, FAILED }

data class HomeActionFeedback(val action: String, val phase: HomeActionPhase, val detail: String)
