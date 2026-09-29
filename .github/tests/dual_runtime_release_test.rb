# frozen_string_literal: true

require 'fileutils'
require 'base64'
require 'json'
require 'open3'
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

def check(condition, message)
  raise message unless condition
end

def test_built_apks
  sdk = ENV.fetch('ANDROID_HOME')
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
    environment = { 'ANDROID_HOME' => sdk, 'VERSION_NAME' => values.fetch('tag_name'),
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

    %w[swapped unsigned].each do |scenario|
      rejected = File.join(directory, scenario)
      FileUtils.mkdir_p(rejected)
      invalid = environment.dup
      if scenario == 'swapped'
        invalid['STANDALONE_SIGNED_RELEASE_FILE'], invalid['PARASITE_SIGNED_RELEASE_FILE'] =
          invalid['PARASITE_SIGNED_RELEASE_FILE'], invalid['STANDALONE_SIGNED_RELEASE_FILE']
      else
        invalid['PARASITE_SIGNED_RELEASE_FILE'] = File.join(ROOT, 'app/build/outputs/apk/parasite/release/app-parasite-release-unsigned.apk')
      end
      _, _, status = execute(PREPARE, rejected, invalid)
      check(!status.success? && Dir[File.join(rejected, 'release-apks/*.apk')].empty?, "Reject actual #{scenario} pair")
    end
  end
  puts 'PASS built APKs: real SDK signature, identity, version, 16 KB alignment and rejected swapped/unsigned pairs'
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

def signed_fixture(directory)
  sdk = File.join(directory, 'sdk')
  build_tools = File.join(sdk, 'build-tools/37.0.0')
  cmdline = File.join(sdk, 'cmdline-tools/latest/bin')
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
      abort 'Wrong manifest command' unless ARGV.first == 'manifest'
      puts JSON.parse(File.read(file)).fetch(ARGV[1])
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
                                  'signature' => true, 'aligned' => true))
    [flavor, path]
  end
  [paths, { 'ANDROID_HOME' => sdk, 'VERSION_NAME' => '1.54.6', 'VERSION_CODE' => '11',
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
puts 'PASS workflow: paired wiring, original permissions, signing bindings and publication gate'

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

test_prepare('matching signed pair with spaces in paths', valid: true)
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

test_signing(fail_signing: false)
test_signing(fail_signing: false, key_password: '')
test_signing(fail_signing: true)
test_signing(fail_signing: 'parasite')
test_built_apks if ARGV.include?('--built-apks')
