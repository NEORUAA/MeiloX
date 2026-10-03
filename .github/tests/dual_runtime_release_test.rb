# frozen_string_literal: true

require 'fileutils'
require 'base64'
require 'json'
require 'open3'
require 'rexml/document'
require 'tmpdir'
require 'yaml'

ROOT = File.expand_path('../..', __dir__)
WORKFLOW = YAML.safe_load_file(File.join(ROOT, '.github/workflows/main.yml'))
BUILD = WORKFLOW.fetch('jobs').fetch('build')
RELEASE = WORKFLOW.fetch('jobs').fetch('release')
STEPS = BUILD.fetch('steps')
METADATA = STEPS.find { |step| step['id'] == 'release_metadata' }.fetch('run')
PREPARE = STEPS.find { |step| step['id'] == 'prepare_apks' }.fetch('run')
SIGN = STEPS.find { |step| step['id'] == 'sign_apks' }.fetch('run')
FLAVORS = %w[standalone parasite].freeze
APPLICATIONS = { 'standalone' => 'com.neoruaa.meilox', 'parasite' => 'com.neoruaa.meilox.parasite' }.freeze
MODULE_FILES = {
  '/META-INF/xposed/java_init.list' => "com.ljyh.mei.parasite.MeiloXModule\n",
  '/META-INF/xposed/scope.list' => "com.netease.cloudmusic.tv\n",
  '/META-INF/xposed/module.prop' => "minApiVersion=102\ntargetApiVersion=102\nstaticScope=true\nautoHotReload=false\n"
}.freeze
PARASITE_SAVED_STATE_TYPES = %w[
  androidx.compose.runtime.ParcelableSnapshotMutableFloatState
  androidx.compose.runtime.ParcelableSnapshotMutableIntState
  androidx.compose.runtime.ParcelableSnapshotMutableLongState
  androidx.compose.runtime.ParcelableSnapshotMutableState
  androidx.compose.runtime.snapshots.SnapshotStateList
].freeze

def check(condition, message)
  raise message unless condition
end

def test_built_apks
  sdk = ENV.fetch('ANDROID_HOME')
  platform = File.join(sdk, 'platforms/android-37.0')
  check(File.file?(File.join(platform, 'package.xml')) && File.file?(File.join(platform, 'android.jar')),
        'The pinned Android 37.0 platform must be installed')
  package = REXML::Document.new(File.read(File.join(platform, 'package.xml'))).get_elements('//localPackage').first
  check(package&.attributes&.[]('path') == 'platforms;android-37.0' &&
        package.elements['type-details/api-level']&.text == '37.0' &&
        package.elements['type-details/codename']&.text.to_s.empty?,
        'Use the installed stable Android 37.0 platform, not a preview or a renamed directory')
  puts 'PASS built SDK: official stable platforms;android-37.0 package and android.jar'
  analyzer = ENV.fetch('PATH').split(File::PATH_SEPARATOR).map { |path| File.join(path, 'apkanalyzer') }
                .find { |path| File.file?(path) && File.executable?(path) } ||
             File.join(sdk, 'cmdline-tools/latest/bin/apkanalyzer')
  check(File.executable?(analyzer), 'Android SDK APK analyzer is unavailable')
  parasite = File.join(ROOT, 'app/build/outputs/apk/parasite/release/app-parasite-release-unsigned.apk')
  PARASITE_SAVED_STATE_TYPES.each do |name|
    stdout, stderr, status = Open3.capture3(analyzer, 'dex', 'code', '--class', name, parasite)
    check(status.success? && stdout.include?('.implements Landroid/os/Parcelable;') &&
          stdout.match?(/\.field public static final CREATOR:Landroid\/os\/Parcelable\$Creator;/),
          "Preserve parasite saved-state Parcelable name and CREATOR for #{name}: #{stderr}")
  end
  puts 'PASS built APK: stable parasite Compose saved-state Parcelable names and platform CREATOR fields'
  Dir.mktmpdir('meilox-release-pair-') do |directory|
    outputs = File.join(directory, 'outputs')
    stdout, stderr, status = execute(METADATA, ROOT, 'GITHUB_OUTPUT' => outputs, 'GITHUB_RUN_NUMBER' => '42')
    check(status.success?, "Built metadata: #{stdout} #{stderr}")
    values = File.read(outputs).lines.to_h { |line| line.strip.split('=', 2) }
    keystore = File.join(directory, 'fixture-only.keystore')
    _, stderr, status = Open3.capture3('keytool', '-genkeypair', '-alias', 'fixture', '-keystore', keystore,
                                      '-storepass', 'fixture-only', '-keypass', 'fixture-only', '-keyalg', 'RSA',
                                      '-keysize', '2048', '-validity', '1', '-dname', 'CN=MeiloX Packaging Test')
    check(status.success?, "Temporary fixture key: #{stderr}")
    environment = { 'ANDROID_HOME' => sdk, 'PATH' => "#{File.dirname(analyzer)}:#{ENV.fetch('PATH')}",
                    'VERSION_NAME' => values.fetch('tag_name'),
                    'VERSION_CODE' => values.fetch('version_code') }
    FLAVORS.each do |flavor|
      relative = "app/build/outputs/apk/#{flavor}/release"
      FileUtils.mkdir_p(File.join(directory, relative))
      %W[output-metadata.json app-#{flavor}-release-unsigned.apk].each do |name|
        FileUtils.copy_file(File.join(ROOT, relative, name), File.join(directory, relative, name))
      end
    end
    signing_outputs = File.join(directory, 'signing-outputs')
    runner_temp = File.join(directory, 'runner-temp')
    FileUtils.mkdir_p(runner_temp)
    signing_environment = { 'ANDROID_HOME' => sdk, 'RUNNER_TEMP' => runner_temp, 'GITHUB_OUTPUT' => signing_outputs,
                            'SIGNING_KEY' => Base64.strict_encode64(File.binread(keystore)), 'KEY_ALIAS' => 'fixture',
                            'KEY_STORE_PASSWORD' => 'fixture-only', 'KEY_PASSWORD' => 'fixture-only' }
    stdout, stderr, status = execute(SIGN, directory, signing_environment)
    check(status.success?, "Workflow SDK signing: #{stdout} #{stderr}")
    check(Dir[File.join(runner_temp, '*')].empty?, 'Delete the decoded fixture key after signing')
    signed = File.read(signing_outputs).lines.to_h { |line| line.strip.split('=', 2) }
    FLAVORS.each do |flavor|
      environment["#{flavor.upcase}_SIGNED_RELEASE_FILE"] = File.expand_path(signed.fetch("#{flavor}_file"), directory)
    end
    stdout, stderr, status = execute(PREPARE, directory, environment)
    check(status.success?, "Built pair preparation: #{stdout} #{stderr}")
    check(Dir[File.join(directory, 'release-apks/*.apk')].size == 2, 'Export both real signed APKs')

    missing_scope = File.join(directory, 'parasite-missing-scope.apk')
    FileUtils.copy_file(environment.fetch('PARASITE_SIGNED_RELEASE_FILE'), missing_scope)
    _, stderr, status = Open3.capture3('zip', '-qd', missing_scope, 'META-INF/xposed/scope.list')
    check(status.success?, "Remove only the fixture scope: #{stderr}")
    aligned = File.join(directory, 'parasite-missing-scope-aligned.apk')
    build_tools = File.join(sdk, 'build-tools/37.0.0')
    _, stderr, status = Open3.capture3(File.join(build_tools, 'zipalign'), '-P', '16', '4', missing_scope, aligned)
    check(status.success?, "Align the altered fixture: #{stderr}")
    _, stderr, status = Open3.capture3(File.join(build_tools, 'apksigner'), 'sign', '--ks', keystore,
                                      '--ks-key-alias', 'fixture', '--ks-pass', 'pass:fixture-only', aligned)
    check(status.success?, "Sign the altered fixture: #{stderr}")
    _, stderr, status = Open3.capture3(File.join(build_tools, 'apksigner'), 'verify', aligned)
    check(status.success?, "Altered fixture has a valid signature: #{stderr}")

    %w[swapped unsigned missing-scope].each do |scenario|
      rejected = File.join(directory, scenario)
      FileUtils.mkdir_p(rejected)
      invalid = environment.dup
      if scenario == 'swapped'
        invalid['STANDALONE_SIGNED_RELEASE_FILE'], invalid['PARASITE_SIGNED_RELEASE_FILE'] =
          invalid['PARASITE_SIGNED_RELEASE_FILE'], invalid['STANDALONE_SIGNED_RELEASE_FILE']
      elsif scenario == 'unsigned'
        invalid['PARASITE_SIGNED_RELEASE_FILE'] = File.join(ROOT, 'app/build/outputs/apk/parasite/release/app-parasite-release-unsigned.apk')
      else
        invalid['PARASITE_SIGNED_RELEASE_FILE'] = aligned
      end
      _, _, status = execute(PREPARE, rejected, invalid)
      check(!status.success? && Dir[File.join(rejected, 'release-apks/*.apk')].empty?, "Reject actual #{scenario} pair")
    end
  end
  puts 'PASS built APKs: real SDK signature, identity, version, 16 KB alignment, runtime declarations and rejected swapped/unsigned/missing-scope pairs'
  puts 'Temporary fixture keys/APKs removed; no production credentials, installation or upload used'
end

def execute(script, directory, environment = {})
  Open3.capture3(environment, 'bash', '-euo', 'pipefail', '-c', script, chdir: directory)
end

def metadata_fixture(directory, version = '1.54.6')
  FLAVORS.to_h do |flavor|
    path = File.join(directory, "app/build/outputs/apk/#{flavor}/release")
    FileUtils.mkdir_p(path)
    File.write(File.join(path, "app-#{flavor}-release-unsigned.apk"), 'unsigned fixture')
    document = {
      'artifactType' => { 'type' => 'APK' }, 'applicationId' => APPLICATIONS.fetch(flavor),
      'variantName' => "#{flavor}Release",
      'elements' => [{ 'type' => 'SINGLE', 'filters' => [], 'versionCode' => 11,
                       'versionName' => version, 'outputFile' => "app-#{flavor}-release-unsigned.apk" }]
    }
    [flavor, document]
  end
end

def test_metadata(name, valid:, version: '1.54.6')
  Dir.mktmpdir('meilox-metadata-') do |directory|
    documents = metadata_fixture(directory, version)
    yield documents, directory if block_given?
    documents.each do |flavor, document|
      File.write(File.join(directory, "app/build/outputs/apk/#{flavor}/release/output-metadata.json"), JSON.generate(document))
    end
    output = File.join(directory, 'outputs')
    File.write(output, '')
    stdout, stderr, status = execute(METADATA, directory, 'GITHUB_OUTPUT' => output, 'GITHUB_RUN_NUMBER' => '42')
    check(status.success? == valid, "#{name}: unexpected status #{status.exitstatus}: #{stdout} #{stderr}")
    result = File.read(output).lines.to_h { |line| line.strip.split('=', 2) }
    if valid
      FLAVORS.each do |flavor|
        check(result["#{flavor}_artifact_name"] == "MeiloX-#{flavor}-#{version}-42", "#{name}: artifact name")
      end
      check(result['tag_name'] == version && result['version_code'] == '11', "#{name}: version")
      check(result['release_name'].match?(/\A\d{6}_#{Regexp.escape(version)}\z/), "#{name}: release name")
    else
      check(result.empty?, "#{name}: invalid metadata must not publish partial outputs")
    end
  end
  puts "PASS metadata: #{name}"
end

def manifest_fixture(flavor)
  components = if flavor == 'standalone'
    <<~XML
      <activity android:name="com.ljyh.mei.MainActivity" android:exported="true">
        <intent-filter>
          <action android:name="android.intent.action.MAIN"/>
          <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
      </activity>
      <service android:name="com.ljyh.mei.playback.MusicService"/>
    XML
  else
    <<~XML
      <activity android:name="com.ljyh.mei.parasite.helper.MicrophonePermissionActivity" android:exported="true" android:excludeFromRecents="true" android:screenOrientation="portrait"/>
      <service android:name="com.ljyh.mei.parasite.helper.MicrophoneCaptureService" android:exported="true" android:foregroundServiceType="microphone"/>
      <activity android:name="com.ljyh.mei.parasite.helper.LyricsPipActivity" android:exported="true" android:excludeFromRecents="true" android:screenOrientation="portrait" android:supportsPictureInPicture="true" android:resizeableActivity="true"/>
      <receiver android:name="com.ljyh.mei.parasite.helper.LyricsPipActionReceiver" android:exported="false"/>
    XML
  end
  <<~XML
    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
      <uses-permission android:name="android.permission.RECORD_AUDIO"/>
      #{flavor == 'parasite' ? '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>' : ''}
      <application android:name="com.ljyh.mei.AppContext">#{components}</application>
    </manifest>
  XML
end

def signed_fixture(directory)
  sdk = File.join(directory, 'sdk')
  build_tools = File.join(sdk, 'build-tools/37.0.0')
  cmdline = File.join(sdk, 'cmdline-tools/22.0/bin')
  FileUtils.mkdir_p([build_tools, cmdline])
  tool = File.join(directory, 'sdk-fixture.rb')
  File.write(tool, <<~'RUBY')
    #!/usr/bin/env ruby
    require 'json'
    file = ARGV.last
    abort 'Missing fixture' unless file && File.file?(file)
    case File.basename($PROGRAM_NAME)
    when 'apksigner'
      if ARGV.first == 'sign'
        abort 'Password must use the environment' unless ARGV[ARGV.index('--ks-pass') + 1] == 'env:KEY_STORE_PASSWORD'
        if ENV.fetch('KEY_PASSWORD').empty?
          abort 'Optional key password must be omitted' if ARGV.include?('--key-pass')
        else
          abort 'Key password must use the environment' unless ARGV[ARGV.index('--key-pass') + 1] == 'env:KEY_PASSWORD'
        end
        key = ARGV[ARGV.index('--ks') + 1]
        abort 'Fixture key is not private' unless File.stat(key).mode & 0o077 == 0
        abort 'Fixture key is missing' unless File.read(key) == 'fixture-key'
        abort 'Synthetic signer failure' if ENV['FAIL_SIGNING'] == 'true' ||
                                            (ENV['FAIL_SIGNING'] == 'parasite' && file.include?('/parasite/'))
        output = ARGV[ARGV.index('--out') + 1]
        File.write(output, File.read(file))
      else
        abort 'Wrong signature command' unless ARGV.first == 'verify'
        exit(JSON.parse(File.read(file)).fetch('signature') ? 0 : 1)
      end
    when 'zipalign'
      abort 'Missing 16 KB alignment check' unless ARGV[0...-1] == %w[-c -P 16 4]
      exit(JSON.parse(File.read(file)).fetch('aligned') ? 0 : 1)
    when 'apkanalyzer'
      document = JSON.parse(File.read(file))
      case ARGV.first
      when 'manifest'
        abort 'Synthetic manifest inspection failure' if ARGV[1] == 'print' && document['manifest-print-failure']
        if ARGV[1] == 'print'
          puts document.fetch('manifest')
          exit
        end
        puts document.fetch(ARGV[1])
      when 'files'
        if ARGV[1] == 'list'
          abort 'Wrong file listing command' unless ARGV[0...-1] == %w[files list --files-only]
          abort 'Synthetic file inspection failure' if document['files-list-failure']
          puts ['/AndroidManifest.xml', *document.fetch('files').keys]
        else
          abort 'Wrong file read command' unless ARGV[0...-2] == %w[files cat --file]
          print document.fetch('files').fetch(ARGV[-2]) { abort 'Missing declared module file' }
        end
      when 'dex'
        abort 'Wrong class inspection command' unless ARGV[0...-2] == %w[dex code --class]
        name = ARGV[-2]
        if name == 'com.ljyh.mei.parasite.MeiloXModule'
          exit(document.fetch('module-class') ? 0 : 1)
        end
        print document.fetch('saved-state-classes', {}).fetch(name) { abort 'Missing saved-state class' }
      else
        abort 'Unexpected analyzer command'
      end
    else
      abort 'Unexpected SDK command'
    end
  RUBY
  File.chmod(0o755, tool)
  File.symlink(tool, File.join(build_tools, 'apksigner'))
  File.symlink(tool, File.join(build_tools, 'zipalign'))
  File.symlink(tool, File.join(cmdline, 'apkanalyzer'))
  paths = FLAVORS.to_h do |flavor|
    path = File.join(directory, 'signed files', "#{flavor}.apk")
    FileUtils.mkdir_p(File.dirname(path))
    File.write(path, JSON.generate('application-id' => APPLICATIONS.fetch(flavor),
                                  'version-name' => '1.54.6', 'version-code' => '11',
                                  'manifest' => manifest_fixture(flavor),
                                  'files' => flavor == 'parasite' ? MODULE_FILES : {},
                                  'module-class' => flavor == 'parasite',
                                  'saved-state-classes' => flavor == 'parasite' ? PARASITE_SAVED_STATE_TYPES.to_h { |name|
                                    [name, ".implements Landroid/os/Parcelable;\n.field public static final CREATOR:Landroid/os/Parcelable$Creator;\n"]
                                  } : {},
                                  'signature' => true, 'aligned' => true))
    [flavor, path]
  end
  [paths, { 'ANDROID_HOME' => sdk, 'PATH' => "#{cmdline}:#{ENV.fetch('PATH')}",
            'VERSION_NAME' => '1.54.6', 'VERSION_CODE' => '11',
            'STANDALONE_SIGNED_RELEASE_FILE' => paths.fetch('standalone'),
            'PARASITE_SIGNED_RELEASE_FILE' => paths.fetch('parasite') }]
end

def test_signing(fail_signing:, key_password: 'fixture-password')
  Dir.mktmpdir('meilox-signing-') do |directory|
    paths, environment = signed_fixture(directory)
    metadata_fixture(directory).each do |flavor, document|
      relative = "app/build/outputs/apk/#{flavor}/release"
      File.write(File.join(directory, relative, 'output-metadata.json'), JSON.generate(document))
      FileUtils.copy_file(paths.fetch(flavor), File.join(directory, relative, document['elements'][0]['outputFile']))
    end
    runner_temp = File.join(directory, 'runner-temp')
    FileUtils.mkdir_p(runner_temp)
    output = File.join(directory, 'signing-outputs')
    stdout, stderr, status = execute(SIGN, directory, environment.merge(
      'RUNNER_TEMP' => runner_temp, 'GITHUB_OUTPUT' => output, 'SIGNING_KEY' => Base64.strict_encode64('fixture-key'),
      'KEY_ALIAS' => 'fixture', 'KEY_STORE_PASSWORD' => 'fixture-password', 'KEY_PASSWORD' => key_password,
      'FAIL_SIGNING' => fail_signing.to_s
    ))
    check(status.success? == !fail_signing, "Signing status: #{stdout} #{stderr}")
    check(Dir[File.join(runner_temp, '*')].empty?, 'Always delete the decoded signing key')
    check(!(stdout + stderr).include?('fixture-password'), 'Do not log passwords')
    unless fail_signing
      values = File.read(output).lines.to_h { |line| line.strip.split('=', 2) }
      FLAVORS.each do |flavor|
        check(File.read(File.join(directory, values.fetch("#{flavor}_file"))) == File.read(paths.fetch(flavor)), 'Sign each exact APK')
      end
    end
  end
  puts "PASS signing: failure=#{fail_signing}, optional password=#{key_password.empty?}, private key cleanup"
end

def test_prepare(name, valid:)
  Dir.mktmpdir('meilox-signed-') do |directory|
    paths, environment = signed_fixture(directory)
    yield paths, environment if block_given?
    stdout, stderr, status = execute(PREPARE, directory, environment)
    check(status.success? == valid, "#{name}: unexpected status #{status.exitstatus}: #{stdout} #{stderr}")
    exports = Dir[File.join(directory, 'release-apks/*.apk')]
    if valid
      check(exports.size == 2, "#{name}: two exports required")
      FLAVORS.each do |flavor|
        check(File.binread(File.join(directory, "release-apks/MeiloX-#{flavor}.apk")) == File.binread(paths.fetch(flavor)),
              "#{name}: mislabeled or modified artifact")
      end
    else
      check(exports.empty?, "#{name}: do not export a partial or invalid pair")
    end
  end
  puts "PASS signed preparation: #{name}"
end

events = WORKFLOW['on'] || WORKFLOW[true] # Psych uses YAML 1.1's boolean interpretation of "on".
check(events.keys.sort == %w[push workflow_dispatch], 'Existing triggers must be preserved')
check(WORKFLOW['permissions'] == { 'contents' => 'read' }, 'Build must not acquire release permissions')
check(BUILD['environment'] == 'release', 'Keep the existing signing environment')
check(RELEASE['if'] == "github.event_name == 'workflow_dispatch'" && RELEASE['needs'] == 'build', 'Only manual paired builds publish')
check(RELEASE['permissions'] == { 'contents' => 'write' }, 'Only the release job needs contents write')
check((STEPS + RELEASE.fetch('steps')).none? { |step| step['continue-on-error'] || step['if'] },
      'Do not publish after a failed prerequisite')
check(STEPS.count { |step| step['uses'] == 'actions/upload-artifact@v4' } == 2, 'Exactly two upload artifacts')
check(RELEASE.fetch('steps').count { |step| step['uses'] == 'actions/download-artifact@v4' } == 2, 'Exactly two release downloads')

gradle = STEPS.find { |step| step['id'] == 'build_apks' }
check(gradle.fetch('run').split == %w[./gradlew :app:testStandaloneDebugUnitTest :app:testParasiteDebugUnitTest
                                          :app:assembleStandaloneRelease :app:assembleParasiteRelease], 'Build and test both runtimes')
sdk_setup = STEPS.select { |step| step['uses'] == 'android-actions/setup-android@v4' }
check(sdk_setup.size == 1, 'Initialize the Android SDK and command-line tool PATH exactly once')
sdk_install = STEPS.find { |step| step['name'] == 'Install Android build tools' }
check(sdk_install&.fetch('run') == "sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0'",
      'Install the published Android 37.0 package, not the unavailable android-37 identifier')
java_index = STEPS.index { |step| step['uses'] == 'actions/setup-java@v6' }
check(java_index && java_index < STEPS.index(sdk_setup.first) &&
      STEPS.index(sdk_setup.first) < STEPS.index(sdk_install) && STEPS.index(sdk_install) < STEPS.index(gradle),
      'Set up Java and Android SDK before installing tools or invoking Gradle')
signer = STEPS.find { |step| step['id'] == 'sign_apks' }
{ 'SIGNING_KEY' => 'SIGNING_KEY', 'KEY_ALIAS' => 'ALIAS', 'KEY_STORE_PASSWORD' => 'KEY_STORE_PASSWORD',
  'KEY_PASSWORD' => 'KEY_PASSWORD' }.each do |input, secret|
  check(signer.dig('env', input) == "${{ secrets.#{secret} }}", 'Do not change existing signing identities')
end
check(STEPS.index(signer) > STEPS.index { |step| step['id'] == 'release_metadata' }, 'Validate metadata before signing')
prepare_index = STEPS.index { |step| step['id'] == 'prepare_apks' }
FLAVORS.each do |flavor|
  prepare = STEPS.fetch(prepare_index)
  check(prepare.dig('env', "#{flavor.upcase}_SIGNED_RELEASE_FILE") == "${{ steps.sign_apks.outputs.#{flavor}_file }}", 'Verify the correct signed output')
  key = "#{flavor}_artifact_name"
  check(BUILD.dig('outputs', key) == "${{ steps.release_metadata.outputs.#{key} }}", 'Missing paired output')
  upload = STEPS.find { |step| step.dig('with', 'name') == "${{ steps.release_metadata.outputs.#{key} }}" }
  check(upload&.fetch('uses') == 'actions/upload-artifact@v4' && STEPS.index(upload) > prepare_index, 'Upload only verified pairs')
  check(upload.dig('with', 'path') == "release-apks/MeiloX-#{flavor}.apk" &&
        upload.dig('with', 'if-no-files-found') == 'error', 'Unambiguous, required upload')
  download = RELEASE.fetch('steps').find { |step| step.dig('with', 'name') == "${{ needs.build.outputs.#{key} }}" }
  check(download&.fetch('uses') == 'actions/download-artifact@v4' && download.dig('with', 'path') == 'release-artifact',
        'Download each exact paired artifact')
end
publisher = RELEASE.fetch('steps').find { |step| step['uses']&.start_with?('ncipollo/release-action@') }
check(publisher.dig('with', 'artifacts').split(',').sort == FLAVORS.map { |flavor| "release-artifact/MeiloX-#{flavor}.apk" }.sort,
      'Publish both APKs, never an ambiguous app-release.apk')
(STEPS + RELEASE.fetch('steps')).filter_map { |step| step['run'] }.each do |script|
  _, stderr, status = Open3.capture3('bash', '-n', stdin_data: script)
  check(status.success?, "Shell syntax: #{stderr}")
end
puts 'PASS workflow: SDK initialization order, paired wiring, original permissions, signing bindings and publication gate'

test_metadata('matching production pair', valid: true)
test_metadata('prerelease and build metadata', valid: true, version: '2.0.0-beta.1+42')
test_metadata('mismatched version name', valid: false) { |docs, _| docs['parasite']['elements'][0]['versionName'] = '2.0.0' }
test_metadata('mismatched version code', valid: false) { |docs, _| docs['parasite']['elements'][0]['versionCode'] = 12 }
test_metadata('fractional version code', valid: false) { |docs, _| docs['parasite']['elements'][0]['versionCode'] = 11.5 }
test_metadata('wrong package', valid: false) { |docs, _| docs['standalone']['applicationId'] += '.debug' }
test_metadata('wrong variant', valid: false) { |docs, _| docs['parasite']['variantName'] = 'standaloneRelease' }
test_metadata('wrong artifact type', valid: false) { |docs, _| docs['parasite']['artifactType']['type'] = 'AAB' }
test_metadata('multiple APKs', valid: false) { |docs, _| docs['standalone']['elements'] *= 2 }
test_metadata('unexpected split filter', valid: false) { |docs, _| docs['parasite']['elements'][0]['filters'] = ['arm64'] }
test_metadata('invalid version tag', valid: false, version: "1.54.6\ntag_name=untrusted")
test_metadata('missing APK', valid: false) { |_, dir| File.delete(File.join(dir, 'app/build/outputs/apk/parasite/release/app-parasite-release-unsigned.apk')) }
test_metadata('path escape', valid: false) { |docs, _| docs['standalone']['elements'][0]['outputFile'] = '../other.apk' }

test_prepare('matching signed pair with spaces and versioned SDK tools, without latest', valid: true)
test_prepare('analyzer unavailable on PATH', valid: false) do |_, environment|
  isolated = File.join(environment.fetch('ANDROID_HOME'), 'empty-bin')
  FileUtils.mkdir_p(isolated)
  File.symlink('/bin/bash', File.join(isolated, 'bash'))
  environment['PATH'] = isolated
end
test_prepare('missing second APK', valid: false) { |paths, _| File.delete(paths.fetch('parasite')) }
test_prepare('missing signed output', valid: false) { |_, env| env['STANDALONE_SIGNED_RELEASE_FILE'] = '' }
test_prepare('swapped artifact outputs', valid: false) do |_, env|
  env['STANDALONE_SIGNED_RELEASE_FILE'], env['PARASITE_SIGNED_RELEASE_FILE'] =
    env['PARASITE_SIGNED_RELEASE_FILE'], env['STANDALONE_SIGNED_RELEASE_FILE']
end
{ 'application-id' => 'com.example.wrong', 'version-name' => '2.0.0', 'version-code' => '12',
  'signature' => false, 'aligned' => false }.each do |key, value|
  test_prepare("wrong #{key}", valid: false) do |paths, _|
    path = paths.fetch('parasite')
    document = JSON.parse(File.read(path))
    document[key] = value
    File.write(path, JSON.generate(document))
  end
end

MODULE_FILES.each_key do |entry|
  test_prepare("missing #{entry}", valid: false) do |paths, _|
    path = paths.fetch('parasite')
    document = JSON.parse(File.read(path))
    document.fetch('files').delete(entry)
    File.write(path, JSON.generate(document))
  end
end
{
  'wrong entry class' => ['/META-INF/xposed/java_init.list', "com.example.WrongModule\n"],
  'empty scope' => ['/META-INF/xposed/scope.list', ''],
  'system framework scope' => ['/META-INF/xposed/scope.list', "com.netease.cloudmusic.tv\nandroid\n"],
  'wrong API version' => ['/META-INF/xposed/module.prop', MODULE_FILES.fetch('/META-INF/xposed/module.prop').sub('targetApiVersion=102', 'targetApiVersion=101')],
  'hot reload enabled' => ['/META-INF/xposed/module.prop', MODULE_FILES.fetch('/META-INF/xposed/module.prop').sub('autoHotReload=false', 'autoHotReload=true')]
}.each do |name, (entry, content)|
  test_prepare(name, valid: false) do |paths, _|
    path = paths.fetch('parasite')
    document = JSON.parse(File.read(path))
    document.fetch('files')[entry] = content
    File.write(path, JSON.generate(document))
  end
end
test_prepare('declared module class absent from DEX', valid: false) do |paths, _|
  path = paths.fetch('parasite')
  document = JSON.parse(File.read(path))
  document['module-class'] = false
  File.write(path, JSON.generate(document))
end
PARASITE_SAVED_STATE_TYPES.each do |name|
  test_prepare("renamed saved-state class #{name}", valid: false) do |paths, _|
    path = paths.fetch('parasite')
    document = JSON.parse(File.read(path))
    classes = document.fetch('saved-state-classes')
    classes['obfuscated'] = classes.delete(name)
    File.write(path, JSON.generate(document))
  end
end
{ 'not Parcelable' => ['.implements Landroid/os/Parcelable;', '.implements Ljava/io/Serializable;'],
  'wrong CREATOR type' => ['CREATOR:Landroid/os/Parcelable$Creator;', 'CREATOR:Ljava/lang/Object;'] }.each do |name, (from, to)|
  test_prepare("saved-state class #{name}", valid: false) do |paths, _|
    path = paths.fetch('parasite')
    document = JSON.parse(File.read(path))
    classes = document.fetch('saved-state-classes')
    type = 'androidx.compose.runtime.ParcelableSnapshotMutableState'
    classes[type] = classes.fetch(type).sub(from, to)
    File.write(path, JSON.generate(document))
  end
end
FLAVORS.each do |flavor|
  test_prepare("#{flavor} file inspection failure", valid: false) do |paths, _|
    path = paths.fetch(flavor)
    document = JSON.parse(File.read(path))
    document['files-list-failure'] = true
    File.write(path, JSON.generate(document))
  end
  test_prepare("#{flavor} legacy Xposed entry", valid: false) do |paths, _|
    path = paths.fetch(flavor)
    document = JSON.parse(File.read(path))
    document.fetch('files')['/assets/xposed_init'] = "com.example.LegacyModule\n"
    File.write(path, JSON.generate(document))
  end
end
test_prepare('standalone Xposed declaration leak', valid: false) do |paths, _|
  path = paths.fetch('standalone')
  document = JSON.parse(File.read(path))
  document.fetch('files')['/META-INF/xposed/module.prop'] = MODULE_FILES.fetch('/META-INF/xposed/module.prop')
  File.write(path, JSON.generate(document))
end

{
  'standalone missing launcher' => ['standalone', ->(xml) { xml.elements['manifest/application/activity/intent-filter/category'].remove }],
  'standalone missing playback service' => ['standalone', ->(xml) { xml.elements['manifest/application/service'].remove }],
  'standalone disabled launcher' => ['standalone', ->(xml) { xml.elements['manifest/application/activity'].attributes['android:enabled'] = 'false' }],
  'standalone disabled Application' => ['standalone', ->(xml) { xml.elements['manifest/application'].attributes['android:enabled'] = 'false' }],
  'standalone disabled playback service' => ['standalone', ->(xml) { xml.elements['manifest/application/service'].attributes['android:enabled'] = 'false' }],
  'standalone non-exported launcher' => ['standalone', ->(xml) { xml.elements['manifest/application/activity'].attributes['android:exported'] = 'false' }],
  'standalone wrong Application' => ['standalone', ->(xml) { xml.elements['manifest/application'].attributes['android:name'] = 'com.example.OtherApplication' }],
  'parasite launcher alias leak' => ['parasite', ->(xml) {
    entry = xml.elements['manifest/application'].add_element('activity-alias',
      'android:name' => 'com.example.Launcher', 'android:targetActivity' => 'com.example.Activity')
    filter = entry.add_element('intent-filter')
    filter.add_element('action', 'android:name' => 'android.intent.action.MAIN')
    filter.add_element('category', 'android:name' => 'android.intent.category.LAUNCHER')
  }],
  'parasite own playback component leak' => ['parasite', ->(xml) {
    xml.elements['manifest/application'].add_element('service', 'android:name' => 'com.ljyh.mei.playback.MusicService')
  }],
  'parasite microphone helper missing' => ['parasite', ->(xml) {
    xml.elements['manifest/application/service'].remove
  }],
  'parasite microphone helper disabled' => ['parasite', ->(xml) {
    xml.elements['manifest/application/service'].attributes['android:enabled'] = 'false'
  }],
  'parasite microphone helper non-exported' => ['parasite', ->(xml) {
    xml.elements['manifest/application/service'].attributes['android:exported'] = 'false'
  }],
  'parasite microphone helper wrong type' => ['parasite', ->(xml) {
    xml.elements['manifest/application/service'].attributes['android:foregroundServiceType'] = 'dataSync'
  }],
  'parasite permission helper exposed in recents' => ['parasite', ->(xml) {
    xml.elements['manifest/application/activity'].attributes['android:excludeFromRecents'] = 'false'
  }],
  'parasite microphone permission missing' => ['parasite', ->(xml) {
    xml.elements['manifest/uses-permission'].remove
  }],
  'parasite PiP helper missing' => ['parasite', ->(xml) {
    xml.elements["manifest/application/activity[@android:name='com.ljyh.mei.parasite.helper.LyricsPipActivity']"].remove
  }],
  'parasite PiP helper disabled' => ['parasite', ->(xml) {
    xml.elements["manifest/application/activity[@android:name='com.ljyh.mei.parasite.helper.LyricsPipActivity']"].attributes['android:enabled'] = 'false'
  }],
  'parasite PiP helper non-exported' => ['parasite', ->(xml) {
    xml.elements["manifest/application/activity[@android:name='com.ljyh.mei.parasite.helper.LyricsPipActivity']"].attributes['android:exported'] = 'false'
  }],
  'parasite PiP capability missing' => ['parasite', ->(xml) {
    xml.elements["manifest/application/activity[@android:name='com.ljyh.mei.parasite.helper.LyricsPipActivity']"].attributes['android:supportsPictureInPicture'] = 'false'
  }],
  'parasite PiP helper exposed in recents' => ['parasite', ->(xml) {
    xml.elements["manifest/application/activity[@android:name='com.ljyh.mei.parasite.helper.LyricsPipActivity']"].attributes['android:excludeFromRecents'] = 'false'
  }],
  'parasite PiP helper landscape' => ['parasite', ->(xml) {
    xml.elements["manifest/application/activity[@android:name='com.ljyh.mei.parasite.helper.LyricsPipActivity']"].attributes['android:screenOrientation'] = 'landscape'
  }],
  'parasite PiP receiver missing' => ['parasite', ->(xml) {
    xml.elements['manifest/application/receiver'].remove
  }],
  'parasite PiP receiver exported' => ['parasite', ->(xml) {
    xml.elements['manifest/application/receiver'].attributes['android:exported'] = 'true'
  }],
  'standalone helper microphone permission leak' => ['standalone', ->(xml) {
    xml.root.add_element('uses-permission', 'android:name' => 'android.permission.FOREGROUND_SERVICE_MICROPHONE')
  }],
  'standalone host diagnostic component leak' => ['standalone', ->(xml) {
    xml.elements['manifest/application'].add_element('receiver', 'android:name' => 'com.ljyh.mei.parasite.HostWorkProbeReceiver')
  }],
  'production instrumentation leak' => ['parasite', ->(xml) {
    xml.root.add_element('instrumentation', 'android:name' => 'com.example.TestRunner')
  }]
}.each do |name, (flavor, change)|
  test_prepare(name, valid: false) do |paths, _|
    path = paths.fetch(flavor)
    document = JSON.parse(File.read(path))
    xml = REXML::Document.new(document.fetch('manifest'))
    change.call(xml)
    document['manifest'] = xml.to_s
    File.write(path, JSON.generate(document))
  end
end
FLAVORS.each do |flavor|
  test_prepare("#{flavor} manifest inspection failure", valid: false) do |paths, _|
    path = paths.fetch(flavor)
    document = JSON.parse(File.read(path))
    document['manifest-print-failure'] = true
    File.write(path, JSON.generate(document))
  end
end
test_prepare('malformed production manifest', valid: false) do |paths, _|
  path = paths.fetch('parasite')
  document = JSON.parse(File.read(path))
  document['manifest'] = '<manifest><application>'
  File.write(path, JSON.generate(document))
end

test_signing(fail_signing: false)
test_signing(fail_signing: false, key_password: '')
test_signing(fail_signing: true)
test_signing(fail_signing: 'parasite')
test_built_apks if ARGV.include?('--built-apks')
