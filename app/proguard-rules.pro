-keep class app.vela.VelaVpnService { *; }
# ядро Xray вызывается через reflection
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
