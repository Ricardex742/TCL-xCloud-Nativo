# Better xCloud regional access reference

The fixed country/IP reference table in `RegionBypass.kt` is adapted from
[Better xCloud](https://github.com/redphx/better-xcloud), under the MIT license.

Upstream revision: `ea4d19f2800cee9cfa387a0c9c37b3711cf208c7`.

- [Reference country table](https://github.com/redphx/better-xcloud/blob/ea4d19f2800cee9cfa387a0c9c37b3711cf208c7/src/enums/bypass-servers.ts)
- [xCloud login interception](https://github.com/redphx/better-xcloud/blob/ea4d19f2800cee9cfa387a0c9c37b3711cf208c7/src/utils/xcloud-interceptor.ts)

The native implementation sends the optional `X-Forwarded-For` reference only
with the xCloud login request, without following redirects for that request.
It does not proxy traffic or change the client's actual IP address. Xbox still
decides whether to grant access and which regions and titles to offer.

The complete upstream notice is retained in [LICENSE](LICENSE) and shipped
in the APK as `assets/region-bypass/LICENSE.txt`.
