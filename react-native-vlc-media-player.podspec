Pod::Spec.new do |s|
  s.name         = "react-native-maxplayer-vlc"
  s.version      = "1.0.100"
  s.summary      = "VLC player"
  s.requires_arc = true
  s.author       = { 'nemanja@egalactic.com' => 'nemanja@egalactic.com' }
  s.license      = 'MIT'
  s.homepage     = 'https://github.com/Nemanja02/react-native-maxplayer-vlc.git'
  s.source       = { :git => "https://github.com/Nemanja02/react-native-maxplayer-vlc.git" }
  s.source_files = 'ios/RCTVLCPlayer/*'
  s.ios.deployment_target = "8.4"
  s.tvos.deployment_target = "10.2"
  s.static_framework = true
  s.dependency 'React'
  s.ios.dependency 'MobileVLCKit', '3.5.1'
  s.tvos.dependency 'TVVLCKit', '3.5.1'
  # 3.6.0 brljavi i iima deadlock kad se destroyuje plaeyr, moguće da mora da se promeni način destroya
end
