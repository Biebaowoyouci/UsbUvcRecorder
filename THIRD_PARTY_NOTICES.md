# Third-party notices

This project vendors a source fork of
[UVCAndroid](https://github.com/shiyinghan/UVCAndroid) 1.0.13, distributed under
the Apache License 2.0. The local fork adds the classic Serenegiant-compatible
USB bandwidth factor and direct ANativeWindow preview path. It includes native
components derived from libuvc, libusb, libjpeg-turbo and libyuv; their license
files remain in the vendored source tree.

The bandwidth-factor behavior was cross-checked against
[saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera), also distributed
under the Apache License 2.0.

AndroidX and Material Components dependencies are used under their respective
Apache License 2.0 terms.

[RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.7.5 is used for
RTMP/RTMPS publishing under the Apache License 2.0.

[AndroidX Media3](https://github.com/androidx/media) 1.10.1, including its
ExoPlayer and RTMP data source modules, is used for RTMP input playback under
the Apache License 2.0.
