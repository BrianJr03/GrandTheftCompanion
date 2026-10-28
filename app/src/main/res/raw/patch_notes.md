## v0.16.0

**Smooth follow camera on the atlas page**

The atlas now follows you with a camera that eases, not a camera that snaps. Position samples arrive 20 times a second from the plugin but the camera glides at the display's refresh rate, so the dot stays near the center at a walk and lags only a touch at highway speed. Stand still, and the atlas holds steady with no shimmer.

**Pan, fling, and pinch do what you'd expect**

A one-finger drag turns follow off and leaves a bit of coast at lift, like a map on your phone. A pinch keeps follow on and zooms about the point between your fingers. When you let go of a pinch, the atlas settles on the nearest preset so the view stays legible. (Turn that settle off from Settings → atlas & minimap if you'd rather land exactly where your fingers left you.)

**+ / - pill with zoom presets**

A compact pill on the right edge of the atlas has **+**, the current level, and **-**. Taps ease between presets at `fit`, `8`, `4`, `2`, `1`, and `0.5` meters per screen pixel. Long-press **-** jumps to fit.

**Center on player**

A new location icon in the atlas header flies the camera to the player with a short arc. Double-tapping the player dot does the same thing.

**Follow smoothing is a setting**

Settings → atlas & minimap → **follow smoothing** picks tight / normal / loose. Loose feels like the camera's trailing you, tight like it's locked on. Normal is the default.

**Fresh installs open on you**

A new install opens the atlas at the finest preset with follow turned on, so the first position frame flies the camera straight to where you are. Change either in Settings and the choice sticks per game.
