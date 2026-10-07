/* AM++ Android Canvas bridge for the pinned Apache-2.0 Backdrop lens shader.
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md. */
package com.kyant.backdrop

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import com.kyant.backdrop.internal.RoundedRectRefractionShaderString

/** Use exactly the same refraction field for native Compose chrome and module glass islands. */
@RequiresApi(33)
fun nativeRefractionShader(): RuntimeShader = RuntimeShader(RoundedRectRefractionShaderString)
