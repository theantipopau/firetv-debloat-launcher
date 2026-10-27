# Cleaning up Amazon's home screen ads and background apps

Amazon's home screen cannot be removed (its launcher is a protected package), so this is belt and braces: our launcher
sits in front, and the steps below make Amazon's screen quiet if you ever land on it. Everything is reversible.

## Run the script
```
tools/firetv-tune.sh <tv-ip>      # undo: tools/firetv-undo.sh <tv-ip>
```
It disables (per user, not uninstalled): `com.amazon.shoptv.firetv.client`, `com.amazon.shoptv.client`, `com.amazon.tv.acr`
(content recognition), `com.amazon.hybridadidservice`, `com.amazon.sneakpeek`, `com.amazon.ftv.screensaver` (Amazon's ad-supported screensaver).
It also sets `amz_limit_ad_tracking` and `non_amz_limit_ad_tracking` to 1 and halves the animation scales.
Re-enable one: `adb shell pm enable <package>`. Protected packages (`com.amazon.ftvads.deeplinking`, `com.amazon.tv.csapp`,
`com.amazon.tv.launcher`) refuse to be disabled.

## Block the ad hosts on your network
Amazon's banners and sponsored rows are fetched from its servers. In Pi-hole (Domains > Add to denylist), AdGuard Home or your
router's DNS filter, add:
```
aax-us-east.amazon-adsystem.com   s.amazon-adsystem.com   c.amazon-adsystem.com
mads.amazon.com                   unagi-na.amazon.com     unagi.amazon.com
device-metrics-us.amazon.com      device-metrics-us-2.amazon.com
```
These are commonly blocked Fire TV ad and telemetry hosts; none are needed for streaming apps. Then make sure the TV uses that DNS
(a DHCP reservation with your Pi-hole as DNS server, or set it in the router). Test from the TV:
`adb shell ping -c1 mads.amazon.com` should resolve to `0.0.0.0` or `127.0.0.1`.

If you want to block exactly what your own TV asks for, set Pi-hole's privacy level to show domains, filter the query log by the TV's IP
and add the Amazon hosts you see.

## What not to do
Do not block `amazon.com`, `amazonaws.com` or `cloudfront.net` wholesale: Prime Video, Alexa and Amazon Appstore break.
