#import "FlutterPluginAudioPlugin.h"
#if __has_include(<flutter_plugin_audio/flutter_plugin_audio-Swift.h>)
#import <flutter_plugin_audio/flutter_plugin_audio-Swift.h>
#else
// Support project import fallback if the generated compatibility header
// is not copied when this plugin is created as a library.
// https://forums.swift.org/t/swift-static-libraries-dont-copy-generated-objective-c-header/19816
#import "flutter_plugin_audio-Swift.h"
#endif

@implementation FlutterPluginAudioPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  [SwiftFlutterPluginAudioPlugin registerWithRegistrar:registrar];
}
@end
