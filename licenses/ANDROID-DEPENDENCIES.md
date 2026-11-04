# Android 运行依赖实际清单

> 以下表格及同目录 `android-runtime-dependencies.json` 保留 main v0.1.3 的发布基线，并非液态玻璃分支当前清单。当前升级声明见 `THIRD_PARTY_NOTICES`，版本由 Gradle 配置/解析确定；正式发布前使用现有 inventory/collect 脚本重新生成。

由 Gradle modernProbeRuntimeClasspath 实际解析结果生成，共 58 个 AAR/JAR。哈希对应构建缓存中的真实归档；许可证声明来自发布 POM，归档内可识别的 LICENSE / NOTICE 文本一同保留。此表不包含构建工具、测试库或手机首次下载的 Ubuntu / Node / DSH。完整法律审阅仍需核对缺失声明和上游要求。

| 组件 | 发布 POM 的许可声明 | 归档 SHA-256 |
|---|---|---|
| `androidx.activity:activity:1.9.0` | The Apache Software License, Version 2.0 | `46fc8e842d9a4e030dfd9e108c3bc08310f922bd7421f29f67dcaaa4adac3764` |
| `androidx.activity:activity-compose:1.9.0` | The Apache Software License, Version 2.0 | `fab1c6f216c44083ddb70d90e248eb34504f846b505aa3c7a832325b6b790d8c` |
| `androidx.activity:activity-ktx:1.9.0` | The Apache Software License, Version 2.0 | `290798b88ce24c897747f15cd25102005a941a2cf4d35efcb8ce2f162c21f589` |
| `androidx.annotation:annotation-experimental:1.4.0` | The Apache Software License, Version 2.0 | `c6eb7e676011ec65b32428373d450debdfc45179c4f8b3a752174fb87c17b08a` |
| `androidx.annotation:annotation-jvm:1.8.0` | The Apache Software License, Version 2.0 | `9aab326d9492800991854360ac248f493ce7f7c3183519309b78ace9e240f6f6` |
| `androidx.arch.core:core-common:2.2.0` | The Apache Software License, Version 2.0 | `65308a06b1c00ee186cb9e19321383f043b993813f1522c47f4a3e3303bdba41` |
| `androidx.arch.core:core-runtime:2.2.0` | The Apache Software License, Version 2.0 | `a1be5e0caa2b07623862af6ae21b3ab0718123245184d0e30dea81b53f990a47` |
| `androidx.autofill:autofill:1.0.0` | The Apache Software License, Version 2.0 | `c9468f56e05006ea151a426c54957cd0799b8b83a579d2846dd22061f33e5ecd` |
| `androidx.collection:collection-jvm:1.4.0` | The Apache Software License, Version 2.0 | `d5cf7b72647c7995071588fe870450ff9c8f127f253d2d4851e161b800f67ae0` |
| `androidx.collection:collection-ktx:1.4.0` | The Apache Software License, Version 2.0 | `c6deada2fac53b8ea6523dbda77597b128006674616f140f04df23264c6d1aa3` |
| `androidx.compose.animation:animation-android:1.6.7` | The Apache Software License, Version 2.0 | `5679bd5b963f9a29069f631db786845c7945bc79b3342b47e7f13e9713fcde67` |
| `androidx.compose.animation:animation-core-android:1.6.7` | The Apache Software License, Version 2.0 | `080b75340aafc4362f2afb9a93705cf664b2537264be72ebf63fbfa822916383` |
| `androidx.compose.foundation:foundation-android:1.6.7` | The Apache Software License, Version 2.0 | `3b6cd27611e2fbd0976ec3896579d45e570b26966cc9898ed0ed03865087bf3c` |
| `androidx.compose.foundation:foundation-layout-android:1.6.7` | The Apache Software License, Version 2.0 | `faf2064b0602d78e6697631f5b0428abea25178adc378484a07605d8ca4d36ac` |
| `androidx.compose.material3:material3-android:1.2.1` | The Apache Software License, Version 2.0 | `d344e0d73060cde4bbc0ed64146986ca1fae0e0ec953ad9698713edd2bd11600` |
| `androidx.compose.material:material-icons-core-android:1.6.0` | The Apache Software License, Version 2.0 | `0400755a3aa7270893445a18cd845e35064c9de02c1c41cf2083ad4724bcac6f` |
| `androidx.compose.material:material-ripple-android:1.6.0` | The Apache Software License, Version 2.0 | `394ba9369f3cbdee4345b429e38d01623dd56cd98b712b40edbe14359b83bce8` |
| `androidx.compose.runtime:runtime-android:1.6.8` | The Apache Software License, Version 2.0 | `a431ba9358d956a19cb020f2ce3c2b14879c421408aaec5fda9644cdf7b50aa7` |
| `androidx.compose.runtime:runtime-saveable-android:1.6.8` | The Apache Software License, Version 2.0 | `ee7ac18bd1385f04320dbddf12250fc341a1fa5ce47401c81442c9c3dca97daf` |
| `androidx.compose.ui:ui-android:1.6.8` | The Apache Software License, Version 2.0 | `c86f1030049bfad9f46a5e7498e13d2583d81a878aec743560e44d2bf6fb065c` |
| `androidx.compose.ui:ui-geometry-android:1.6.8` | The Apache Software License, Version 2.0 | `2f3ffe2689101d740d1a176ae623ac808f154d21c44a0dba69a1dc6f0a945513` |
| `androidx.compose.ui:ui-graphics-android:1.6.8` | The Apache Software License, Version 2.0 | `b34c940c2e8826d25fdacceadb73d9ec4c57af6096a516aea9d2f643f4404b62` |
| `androidx.compose.ui:ui-text-android:1.6.8` | The Apache Software License, Version 2.0 | `cd3d184649de59d0d552b963c3aea6e046735ef14fa9114b9a99eb9b5cbc82e2` |
| `androidx.compose.ui:ui-unit-android:1.6.8` | The Apache Software License, Version 2.0 | `468ae1b39575bd2ebcdef9c89973fbaa56542d1a9b78fa9fe40f88e1369727a3` |
| `androidx.compose.ui:ui-util-android:1.6.8` | The Apache Software License, Version 2.0 | `157df6f376bcb4798be1cb428fbbf5bf781cdc319a0bfb6e2db71fec986372be` |
| `androidx.concurrent:concurrent-futures:1.1.0` | The Apache Software License, Version 2.0 | `0ce067c514a0d1049d1bebdf709e344ed3266fe9744275682937cdcb13334e9e` |
| `androidx.core:core:1.13.1` | The Apache Software License, Version 2.0 | `2c27de199535675005553066597a4b20fa1eea7c228ab4ef6b32b5fe39ca1f59` |
| `androidx.core:core:1.13.1` | The Apache Software License, Version 2.0 | `2c27de199535675005553066597a4b20fa1eea7c228ab4ef6b32b5fe39ca1f59` |
| `androidx.core:core-ktx:1.13.1` | The Apache Software License, Version 2.0 | `19ba50d094c7368ede1b4ccf1195ceb83e35970736593f823e5af716f8d05d70` |
| `androidx.customview:customview-poolingcontainer:1.0.0` | The Apache Software License, Version 2.0 | `3584102fc49bf399c56e3b7be4bfe12000c46112320cd8cf85cc0a8f93f3e752` |
| `androidx.emoji2:emoji2:1.3.0` | The Apache Software License, Version 2.0 | `2bf23818b23a996ddaa1b5fd5bb32129daff6bbb2dce15166e2fccdd2010b1a5` |
| `androidx.interpolator:interpolator:1.0.0` | The Apache Software License, Version 2.0 | `33193135a64fe21fa2c35eec6688f1a76e512606c0fc83dc1b689e37add7732a` |
| `androidx.lifecycle:lifecycle-common-java8:2.8.0` | The Apache Software License, Version 2.0 | `c6deada2fac53b8ea6523dbda77597b128006674616f140f04df23264c6d1aa3` |
| `androidx.lifecycle:lifecycle-common-jvm:2.8.0` | The Apache Software License, Version 2.0 | `61c873a7327c946ec033c310bb98f3f92eeabcede0e1a5200ab8a1896483c7bf` |
| `androidx.lifecycle:lifecycle-livedata-core:2.8.0` | The Apache Software License, Version 2.0 | `ac09ef37b472e642356e500cba306ea775377d4a0646b2854382655ccd14b318` |
| `androidx.lifecycle:lifecycle-process:2.8.0` | The Apache Software License, Version 2.0 | `68fb5e8a196764eeafee6a035bcdf781ba447f269bfecae77eae3145b1ed5084` |
| `androidx.lifecycle:lifecycle-runtime-android:2.8.0` | The Apache Software License, Version 2.0 | `6135c58fe129a784454115804df851f619bd8afaf15024f0099afe2de6be9332` |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.0` | The Apache Software License, Version 2.0 | `5efe6d039388d0e7845cf35e91b1afa4a207ce399daaafb8d3bb2779b9f23401` |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.8.0` | The Apache Software License, Version 2.0 | `774ec46bc6e9b0557b0d7e779d3ec59a9f82a385eb27ef3ac993b7d345234f34` |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0` | The Apache Software License, Version 2.0 | `654293206d0ab91ae30d726fa1c7ccf0c646aadea8b8ef5d8d6da198a018b7c9` |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.0` | The Apache Software License, Version 2.0 | `b905ae59b6f6b0991f66fe23601c4bd843e752468f913f5a3d2060242021cc50` |
| `androidx.profileinstaller:profileinstaller:1.3.1` | The Apache Software License, Version 2.0 | `d0e402ec31f24028a1dc7eb6a0a3f9d9635c1459392cd734396343b73d673948` |
| `androidx.savedstate:savedstate:1.2.1` | The Apache Software License, Version 2.0 | `21a7d4bcf6bdb94ad7b9283801529300b4fbb8808ca4f191e0cdce6fd8e4705a` |
| `androidx.savedstate:savedstate-ktx:1.2.1` | The Apache Software License, Version 2.0 | `8553f87e7136c24ec5243560f48f1c32cba56daa77722f89589a5cafcb8f7894` |
| `androidx.startup:startup-runtime:1.1.1` | The Apache Software License, Version 2.0 | `e0a6329a371262fe4c450372b70fdaf33b769ef6917094723787cfce896b1dd3` |
| `androidx.tracing:tracing:1.0.0` | The Apache Software License, Version 2.0 | `07b8b6139665b884a162eccf97891ca50f7f56831233bf25168ae04f7b568612` |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | The Apache Software License, Version 2.0 | `57e8d93260d18d5b9007c9eed3c64ad159de90c8609ebfc74a347cbd514535a4` |
| `androidx.webkit:webkit:1.10.0` | The Apache Software License, Version 2.0 | `fdc05992036d0665090b77d7050f9babce74dce95b5364c79026d1df4dc46aba` |
| `com.google.guava:listenablefuture:1.0` | POM 未声明，需人工核对 | `e4ad7607e5c0477c6f890ef26a49cb8d1bb4dffb650bab4502afee64644e3069` |
| `commons-codec:commons-codec:1.17.1` | POM 未声明，需人工核对 | `f9f6cb103f2ddc3c99a9d80ada2ae7bf0685111fd6bffccb72033d1da4e6ff23` |
| `commons-io:commons-io:2.16.1` | POM 未声明，需人工核对 | `f41f7baacd716896447ace9758621f62c1c6b0a91d89acee488da26fc477c84f` |
| `dev.chrisbanes.haze:haze-android:0.7.3` | The Apache Software License, Version 2.0 | `fc5a6931b7dd90aa69fc4e0bd714f3a8c7bca63b51679f75113d12d79e756b59` |
| `org.apache.commons:commons-compress:1.27.1` | POM 未声明，需人工核对 | `293d80f54b536b74095dcd7ea3cf0a29bbfc3402519281332495f4420d370d16` |
| `org.apache.commons:commons-lang3:3.16.0` | POM 未声明，需人工核对 | `08709dd74d602b705ce4017d26544210056a4ba583d5b20c09373406fe7a00f8` |
| `org.jetbrains.kotlin:kotlin-stdlib:1.9.24` | The Apache License, Version 2.0 | `858b902696da9cf585ab9d98ffc1c2712269828354dfe9107e3711b084a36468` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1` | The Apache Software License, Version 2.0 | `a134dacf4e6578b29b32e97aa50548d09cb45e3cb3551ce77ac27e55e265d8f5` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1` | The Apache Software License, Version 2.0 | `f3d4f5de1c391bbcc20f3b3435ccbac013521e76b6902d7d59635ec15c1f797e` |
| `org.jetbrains:annotations:23.0.0` | The Apache Software License, Version 2.0 | `7b0f19724082cbfcbc66e5abea2b9bc92cf08a1ea11e191933ed43801eb3cd05` |
