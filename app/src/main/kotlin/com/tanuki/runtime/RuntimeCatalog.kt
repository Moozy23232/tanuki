package com.tanuki.runtime

/** Curated official ARM64 artifacts. Mirrors must match the same official SHA-256. */
data class RuntimeArtifact(val version:String,val filename:String,val sha256:String,val size:Long,
    val series:String="",val lts:Boolean=true) {
    val cacheName:String get()=if(series.isNotEmpty())"ubuntu-$version-arm64.tar.gz" else "node-$version-arm64.tar.gz"
}

object RuntimeCatalog {
    val ubuntu=listOf(
        RuntimeArtifact("24.04.5","ubuntu-base-24.04.5-base-arm64.tar.gz",RuntimeManager.UBUNTU_SHA,29936675,"24.04"),
        RuntimeArtifact("26.04.1","ubuntu-base-26.04.1-base-arm64.tar.gz","5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",35092106,"26.04"),
        RuntimeArtifact("22.04.5","ubuntu-base-22.04.5-base-arm64.tar.gz","075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f",27671306,"22.04"),
        RuntimeArtifact("24.04.4","ubuntu-base-24.04.4-base-arm64.tar.gz","04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",29870567,"24.04"),
        RuntimeArtifact("24.04.3","ubuntu-base-24.04.3-base-arm64.tar.gz","7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048",29865086,"24.04"),
        RuntimeArtifact("22.04.4","ubuntu-base-22.04.4-base-arm64.tar.gz","e48adafaa53f19f648da1894afe02b8347b4f8d821747ae3eae5181be96476c9",27669857,"22.04"),
        RuntimeArtifact("26.04","ubuntu-base-26.04-base-arm64.tar.gz","b2b46a37324ea1954e93f293fe6d7c2241daf2fc298c4022e6e4caceeed74cab",35094845,"26.04"))
    val node=listOf(
        RuntimeArtifact("24.22.2","node-v24.22.2-linux-arm64.tar.gz",RuntimeManager.NODE_SHA,58030966),
        RuntimeArtifact("26.13.0","node-v26.13.0-linux-arm64.tar.gz","439e9c438acdf6a6ffe47418da709153d66c4bed812e30a473faacb107dd2791",62611009),
        RuntimeArtifact("24.10.0","node-v24.10.0-linux-arm64.tar.gz","42776dfc722991b1ac9f302f3d7b5cf989cf7aca519b479c50e59f0663803b73",58570022,lts=false),
        RuntimeArtifact("22.23.4","node-v22.23.4-linux-arm64.tar.gz","f7a1e486dbc8b286847815b477d4fb5e9fb963ed4489c3e1588853d685f589f9",56697252),
        RuntimeArtifact("26.12.1","node-v26.12.1-linux-arm64.tar.gz","a6c01af459dd8972ab3fb08ee5da81244d26ba8c4944a62d5bbba8c597ae154f",62602144,lts=false),
        RuntimeArtifact("26.8.2","node-v26.8.2-linux-arm64.tar.gz","746cdbf21565b4ea06f77642bb0e85466de8bb722242be2c5e006f272c361c63",62057300,lts=false),
        RuntimeArtifact("26.0.0","node-v26.0.0-linux-arm64.tar.gz","c802664b5770102999efacf5717854f7652797db522e8cc5727a6209afea824d",60313464,lts=false),
        RuntimeArtifact("25.9.0","node-v25.9.0-linux-arm64.tar.gz","8fb4283301b8c720fc9f18bffff0f659e72cc14d0cf207a3bb411808aaa73a57",56022578,lts=false),
        RuntimeArtifact("24.22.0","node-v24.22.0-linux-arm64.tar.gz","b7e7991d5407815adfa74a2a9cd4db683552c8ce32ff4a295c22e972849c3f5d",57997377),
        RuntimeArtifact("24.21.0","node-v24.21.0-linux-arm64.tar.gz","724282c3b43aec998aa9527380465b45d229e021b58035f5f4f63095eabfe5d5",57824078),
        RuntimeArtifact("24.18.1","node-v24.18.1-linux-arm64.tar.gz","df224555a083b918e46260cc969838501b9f9a87140c1195e5b9597b56d5dae2",56968528),
        RuntimeArtifact("24.15.0","node-v24.15.0-linux-arm64.tar.gz","73afc234d558c24919875f51c2d1ea002a2ada4ea6f83601a383869fefa64eed",56333323),
        RuntimeArtifact("24.11.1","node-v24.11.1-linux-arm64.tar.gz","0dc93ec5c798b0d347f068db6d205d03dea9a71765e6a53922b682b91265d71f",56043084))
    fun entries(component:RuntimeComponent)=if(component==RuntimeComponent.UBUNTU)ubuntu else node
    fun find(component:RuntimeComponent,version:String)=entries(component).firstOrNull { it.version==version }
    fun ubuntuFromMarker(marker:String?):RuntimeArtifact? {
        val fields=marker?.trim()?.split(Regex("\\s+")) ?: return null
        if(fields.size!=2)return null
        return ubuntu.firstOrNull { it.version==fields[0] && it.sha256==fields[1] }
    }
}
