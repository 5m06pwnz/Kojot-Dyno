Place libllama_server.so here (ARM64 ELF built from llama.cpp server target).

  cmake -B build -DGGML_NATIVE=OFF -DANDROID_ABI=arm64-v8a ...
  # or copy a prebuilt llama-server renamed to libllama_server.so

Android requires the file name to start with "lib" and end with ".so" for packaging
into nativeLibraryDir. LlamaServerProcess executes it from that directory (W^X safe).
