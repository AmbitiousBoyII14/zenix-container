microG payload staging dir.

Drop these three files here (run ../../../tools/fetch-microg.sh):
  gmscore.apk    -> microG Services (com.google.android.gms)
  gsfproxy.apk   -> Services Framework Proxy (com.google.android.gsf)
  fakestore.apk  -> microG Companion / FakeStore (com.android.vending)

Sizes: GmsCore is ~100 MB. They are NOT committed to this repo.
With none of them present the app still builds and runs; Zenix reports the
payload as MISSING and the manual "Install APK" path remains available.
