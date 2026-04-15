package ym.moonlife.api

object MoonlifeProvider {
    @Volatile
    var api: MoonlifeApi? = null
        private set

    fun register(api: MoonlifeApi) {
        this.api = api
    }

    fun unregister() {
        api = null
    }
}
