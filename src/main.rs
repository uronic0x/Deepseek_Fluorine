use fluorine::config::AppConfig;
use fluorine::run_server;
use std::env;

fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let cfg = AppConfig::from_env();
    let port: u16 = env::var("PORT").unwrap_or_else(|_| "8787".into()).parse()?;
    if port == 0 {
        return Err("PORT must be nonzero".into());
    }
    run_server(port, cfg)?;
    Ok(())
}
