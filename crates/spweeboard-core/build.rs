fn main() {
    #[cfg(feature = "uniffi")]
    uniffi::generate_scaffolding("src/uniffi.udl").expect("Failed to generate UniFFI scaffolding");
}
