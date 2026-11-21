package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
    // sherpa-onnx 2.0.2 (#4117) : le code natif lit ce champ (« Failed to get field ID for htpPerfMode » sans lui).
    var htpPerfMode: Boolean = false,
)
