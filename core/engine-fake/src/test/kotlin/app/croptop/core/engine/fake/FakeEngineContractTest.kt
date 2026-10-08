package app.croptop.core.engine.fake

import app.croptop.core.engine.api.Engine
import app.croptop.core.engine.api.EngineContractTest

class FakeEngineContractTest : EngineContractTest() {
    override fun createEngine(): Engine = FakeEngine()
}
