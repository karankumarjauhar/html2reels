fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    if target.contains("android") {
        // Ensure 16 KB ELF segment alignment for Android 15+ even if external wrappers override .cargo/config.toml.
        println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
        println!("cargo:rustc-link-arg=-Wl,-z,common-page-size=16384");
    }
}
