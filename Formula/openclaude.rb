class Openclaude < Formula
  desc "Java + Ink terminal coding agent with a typed stdio backend"
  homepage "https://github.com/hemang11/openclaude"
  url "https://github.com/hemang11/openclaude/releases/download/v0.1.0/openclaude-0.1.0-macos-universal.tar.gz"
  sha256 "69287b9d6f9320fe77c4703b5078bec5fa8bdba3f9d12ccda54911260f4a4eac"
  version "0.1.0"
  license :cannot_represent

  depends_on "node"
  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    env = Language::Java.overridable_java_home_env("21")
    bin.write_env_script libexec/"bin/openclaude", env
    bin.write_env_script libexec/"bin/openclaude-backend", env
  end

  def caveats
    <<~EOS
      OpenClaude stores state under ~/.openclaude by default.
      Set OPENCLAUDE_HOME to isolate local state if needed.

      The `openclaude` command launches the Ink UI.
      The `openclaude-backend` command runs the packaged Java backend directly.
    EOS
  end

  test do
    assert_match "OpenClaude Java CLI bootstrap", shell_output("#{bin}/openclaude-backend --help")
  end
end
