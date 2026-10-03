package com.algorist.markflow.renderer.jcef

import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeFactory

class JcefDerivedRendererRuntimeFactory : DerivedRendererRuntimeFactory {
    override fun create(): DerivedRendererRuntime = JcefDerivedRendererRuntime()
}
