use serde_json::{json, Value};
use std::env;
use std::path::PathBuf;

#[derive(Clone, Debug)]
pub struct AppConfig {
    pub app_name: String,
    pub display_name: String,
    pub subtitle: String,
    pub version: String,
    pub workspace_root: PathBuf,
    pub allow_android_storage: bool,
}

fn non_empty_env(key: &str) -> Option<String> {
    env::var(key)
        .ok()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
}

impl AppConfig {
    pub fn from_env() -> Self {
        let pkg_name = env!("CARGO_PKG_NAME").to_owned();
        let app_name = non_empty_env("APP_NAME").unwrap_or_else(|| pkg_name.clone());
        let display_name = non_empty_env("APP_DISPLAY_NAME").unwrap_or_else(|| app_name.clone());
        let subtitle =
            non_empty_env("APP_SUBTITLE").unwrap_or_else(|| "DeepSeek 本地工作台".to_owned());
        let version = env!("CARGO_PKG_VERSION").to_owned();

        let workspace_raw = non_empty_env("WORKSPACE_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));
        let workspace_root = std::fs::canonicalize(&workspace_raw).unwrap_or(workspace_raw);

        let allow_android_storage = matches!(
            non_empty_env("ALLOW_ANDROID_STORAGE")
                .as_deref()
                .map(str::to_ascii_lowercase)
                .as_deref(),
            Some("1" | "true" | "yes")
        );

        Self {
            app_name,
            display_name,
            subtitle,
            version,
            workspace_root,
            allow_android_storage,
        }
    }
}

pub fn html_escape(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for ch in raw.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            _ => out.push(ch),
        }
    }
    out
}

pub fn render_index_html(template: &str, cfg: &AppConfig) -> String {
    template
        .replace("{{APP_NAME}}", &html_escape(&cfg.app_name))
        .replace("{{APP_DISPLAY_NAME}}", &html_escape(&cfg.display_name))
        .replace("{{APP_SUBTITLE}}", &html_escape(&cfg.subtitle))
        .replace("{{APP_VERSION}}", &html_escape(&cfg.version))
}

pub fn meta_json(cfg: &AppConfig, runtime: Value) -> Value {
    json!({
        "app_name": cfg.app_name,
        "display_name": cfg.display_name,
        "subtitle": cfg.subtitle,
        "version": cfg.version,
        "workspace": cfg.workspace_root.display().to_string(),
        "allow_android_storage": cfg.allow_android_storage,
        "runtime": runtime,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_escaped_app_metadata() {
        let cfg = AppConfig {
            app_name: "my-agent".into(),
            display_name: "My <Agent> & Co".into(),
            subtitle: "Android \"Harness\"".into(),
            version: "0.3.0".into(),
            workspace_root: PathBuf::from("/tmp"),
            allow_android_storage: false,
        };
        let html = render_index_html(
            "<title>{{APP_DISPLAY_NAME}}</title><body data-app=\"{{APP_NAME}}\">{{APP_SUBTITLE}} v{{APP_VERSION}}</body>",
            &cfg,
        );
        assert!(html.contains("My &lt;Agent&gt; &amp; Co"));
        assert!(html.contains("data-app=\"my-agent\""));
        assert!(html.contains("Android &quot;Harness&quot; v0.3.0"));
    }

    #[test]
    fn default_name_comes_from_cargo_pkg() {
        let cfg = AppConfig::from_env();
        assert!(!cfg.app_name.is_empty());
        assert!(!cfg.display_name.is_empty());
    }
}
