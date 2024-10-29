Pod::Spec.new do |s|
  s.name         = "react-native-vlc-media-player"
  s.version      = "1.0.38"
  s.summary      = "VLC player"
  s.requires_arc = true
  s.author       = { 'roshan.milinda' => 'rmilinda@gmail.com' }
  s.license      = 'MIT'
  s.homepage     = 'https://github.com/razorRun/react-native-vlc-media-player.git'
  s.source       = { :git => "https://github.com/razorRun/react-native-vlc-media-player.git" }
  s.source_files = 'ios/RCTVLCPlayer/*'
  # s.platform     = :ios, "8.0"
  s.ios.deployment_target = "8.4"
  s.tvos.deployment_target = "10.2"
  s.static_framework = true
  s.dependency 'React'
  # s.dependency 'MobileVLCKit', '3.3.17'
  # s.dependency 'TVVLCKit', '3.5.1'
  s.ios.dependency 'MobileVLCKit', '3.5.1'
  s.tvos.dependency 'TVVLCKit', '3.5.1'
  # 3.6.0 brljavi i iima deadlock kad se destroyuje plaeyr, moguće da mora da se promeni način destroya
end
