use crate::config::AppConfig;
use crate::harness;
use serde_json::{json, Value};

pub fn tool_definitions(include_device_tools: bool) -> Value {
    let mut defs = json!([
        {
            "type": "function",
            "function": {
                "name": "calc",
                "description": "Evaluate a mathematical expression accurately using local deterministic arithmetic. Supports +, -, *, /, %, ^, parentheses, constants (pi, e), and functions (sqrt, abs, round, floor, ceil, sin, cos, tan, asin, acos, atan, ln, log2, log10, exp, pow, min, max, clamp).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "expression": {
                            "type": "string",
                            "description": "Mathematical expression, e.g. '(1250 * 0.15) + sqrt(144)'"
                        }
                    },
                    "required": ["expression"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "stats",
                "description": "Compute descriptive statistics (count, sum, mean, median, min, max, range, p90, p95, pop_std_dev, sample_std_dev, sample_variance) for a list of numbers.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "numbers": {
                            "type": "array",
                            "items": { "type": "number" },
                            "description": "List of numeric values"
                        }
                    },
                    "required": ["numbers"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "unit_convert",
                "description": "Convert values between units of length (mm, cm, m, km, in, ft, mi), area (m2, km2, ha, sqft), mass (mg, g, kg, oz, lb), temperature (C, F, K), data (B, KB, MB, GB, TB, KiB, MiB, GiB), time (ms, s, min, h, d, wk, yr), energy (J, kJ, Wh, kWh, kcal), and pressure (Pa, kPa, bar, psi, atm).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "value": { "type": "number" },
                        "from": { "type": "string" },
                        "to": { "type": "string" }
                    },
                    "required": ["value", "from", "to"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "date_calc",
                "description": "Perform Gregorian calendar date calculations: 'diff' (days between date and date2), 'workdays' (Mon-Fri business days in [date, date2)), 'add' (add days to date), or 'weekday' (day of week for date). Dates must be YYYY-MM-DD.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "op": {
                            "type": "string",
                            "enum": ["diff", "workdays", "add", "weekday"]
                        },
                        "date": {
                            "type": "string",
                            "description": "Primary date in YYYY-MM-DD format"
                        },
                        "date2": {
                            "type": "string",
                            "description": "Second date in YYYY-MM-DD format (required for 'diff' and 'workdays')"
                        },
                        "days": {
                            "type": "integer",
                            "description": "Signed integer days to add (required for 'add')"
                        }
                    },
                    "required": ["op", "date"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "text_metrics",
                "description": "Count Unicode characters, CJK characters, UTF-8 bytes, whitespace-delimited words, lines, and rough token estimate of a text.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "text": { "type": "string" }
                    },
                    "required": ["text"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "json_query",
                "description": "Validate, format, inspect keys, or query a JSON string using an RFC 6901 JSON Pointer (e.g. '/items/0/price').",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "json_str": { "type": "string", "description": "Raw JSON text" },
                        "op": {
                            "type": "string",
                            "enum": ["query", "keys", "validate", "format"],
                            "description": "Operation to perform (default: 'query' if pointer given, else 'validate')"
                        },
                        "pointer": {
                            "type": "string",
                            "description": "RFC 6901 JSON Pointer path, e.g. '/data/0/id'"
                        }
                    },
                    "required": ["json_str"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "csv_summary",
                "description": "Parse CSV or TSV text and compute row count, column headers, non-empty counts, and numeric column statistics (sum, min, max, mean).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "csv_text": { "type": "string", "description": "CSV or TSV content including header row" },
                        "delimiter": {
                            "type": "string",
                            "description": "Single-character delimiter, e.g. ',' or '\\t' (default ',')"
                        }
                    },
                    "required": ["csv_text"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "text_diff",
                "description": "Compute a deterministic line-by-line diff between old_text and new_text, returning added/removed/unchanged counts and diff lines.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "old_text": { "type": "string" },
                        "new_text": { "type": "string" }
                    },
                    "required": ["old_text", "new_text"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "encode_hash",
                "description": "Deterministic encoding, decoding, and cryptographic/checksum hashing: base64_encode, base64_decode, hex_encode, hex_decode, url_encode, url_decode, crc32, fnv1a_64, sha256.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "op": {
                            "type": "string",
                            "enum": [
                                "base64_encode",
                                "base64_decode",
                                "hex_encode",
                                "hex_decode",
                                "url_encode",
                                "url_decode",
                                "crc32",
                                "fnv1a_64",
                                "sha256"
                            ]
                        },
                        "input": { "type": "string" }
                    },
                    "required": ["op", "input"]
                }
            }
        }
    ]);
    if let Some(arr) = defs.as_array_mut() {
        arr.extend(harness::harness_tool_definitions(include_device_tools));
    }
    defs
}

pub fn execute_tool(name: &str, args_json: &str, cfg: &AppConfig, allow_device_tools: bool) -> String {
    let args: Value = match serde_json::from_str(args_json) {
        Ok(v) => v,
        Err(e) => return json!({ "error": format!("invalid JSON arguments: {e}") }).to_string(),
    };
    if harness::is_device_control_tool(name) && !allow_device_tools {
        return json!({
            "error": format!("tool '{name}' requires device/shell control permission (enable device_tools)")
        })
        .to_string();
    }
    let out = match name {
        "calc" => run_calc(&args),
        "stats" => run_stats(&args),
        "unit_convert" => run_unit_convert(&args),
        "date_calc" => run_date_calc(&args),
        "text_metrics" => run_text_metrics(&args),
        "json_query" => run_json_query(&args),
        "csv_summary" => run_csv_summary(&args),
        "text_diff" => run_text_diff(&args),
        "encode_hash" => run_encode_hash(&args),
        _ => harness::execute_harness_tool(name, &args, cfg),
    };
    match out {
        Ok(v) => v.to_string(),
        Err(e) => json!({ "error": e }).to_string(),
    }
}

// --- Shunting-Yard Math Evaluator ---

#[derive(Clone, Debug, PartialEq)]
enum Tok {
    Num(f64),
    Ident(String),
    Op(char),
    UnaryMinus,
    LParen,
    RParen,
    Comma,
}

fn tokenize(expr: &str) -> Result<Vec<Tok>, String> {
    let chars: Vec<char> = expr.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c.is_whitespace() {
            i += 1;
            continue;
        }
        if c.is_ascii_digit() || c == '.' {
            let start = i;
            while i < chars.len() && (chars[i].is_ascii_digit() || chars[i] == '.') {
                i += 1;
            }
            if i < chars.len() && (chars[i] == 'e' || chars[i] == 'E') {
                i += 1;
                if i < chars.len() && (chars[i] == '+' || chars[i] == '-') {
                    i += 1;
                }
                while i < chars.len() && chars[i].is_ascii_digit() {
                    i += 1;
                }
            }
            let s: String = chars[start..i].iter().collect();
            let v: f64 = s.parse().map_err(|_| format!("invalid number: {s}"))?;
            out.push(Tok::Num(v));
            continue;
        }
        if c.is_ascii_alphabetic() || c == '_' {
            let start = i;
            while i < chars.len() && (chars[i].is_ascii_alphanumeric() || chars[i] == '_') {
                i += 1;
            }
            let ident: String = chars[start..i].iter().collect();
            out.push(Tok::Ident(ident.to_ascii_lowercase()));
            continue;
        }
        match c {
            '(' => out.push(Tok::LParen),
            ')' => out.push(Tok::RParen),
            ',' => out.push(Tok::Comma),
            '+' | '-' | '*' | '/' | '%' | '^' => {
                let expect_unary = matches!(
                    out.last(),
                    None | Some(Tok::Op(_))
                        | Some(Tok::UnaryMinus)
                        | Some(Tok::LParen)
                        | Some(Tok::Comma)
                );
                if expect_unary && c == '-' {
                    out.push(Tok::UnaryMinus);
                } else if expect_unary && c == '+' {
                    // unary plus is a no-op
                } else {
                    out.push(Tok::Op(c));
                }
            }
            _ => return Err(format!("unexpected character: '{c}'")),
        }
        i += 1;
    }
    Ok(out)
}

#[derive(Clone, Debug)]
enum OpItem {
    Op(char),
    UnaryMinus,
    Func(String),
    LParen,
}

fn prec(op: &OpItem) -> u8 {
    match op {
        OpItem::Op('+') | OpItem::Op('-') => 1,
        OpItem::Op('*') | OpItem::Op('/') | OpItem::Op('%') => 2,
        OpItem::UnaryMinus => 3,
        OpItem::Op('^') => 4,
        _ => 0,
    }
}

fn is_right_assoc(op: &OpItem) -> bool {
    matches!(op, OpItem::Op('^') | OpItem::UnaryMinus)
}

pub fn eval_expr(expr: &str) -> Result<f64, String> {
    if expr.len() > 2048 {
        return Err("expression too long".into());
    }
    let tokens = tokenize(expr)?;
    let mut output: Vec<Tok> = Vec::new();
    let mut ops: Vec<OpItem> = Vec::new();

    for (idx, tok) in tokens.iter().enumerate() {
        match tok {
            Tok::Num(n) => output.push(Tok::Num(*n)),
            Tok::Ident(name) => {
                let is_call = matches!(tokens.get(idx + 1), Some(Tok::LParen));
                if is_call {
                    ops.push(OpItem::Func(name.clone()));
                } else {
                    match name.as_str() {
                        "pi" => output.push(Tok::Num(std::f64::consts::PI)),
                        "e" => output.push(Tok::Num(std::f64::consts::E)),
                        _ => return Err(format!("unknown constant: {name}")),
                    }
                }
            }
            Tok::Comma => {
                while let Some(top) = ops.last() {
                    if matches!(top, OpItem::LParen) {
                        break;
                    }
                    let item = ops.pop().unwrap();
                    push_op(&mut output, item)?;
                }
            }
            Tok::LParen => ops.push(OpItem::LParen),
            Tok::RParen => {
                let mut found = false;
                while let Some(item) = ops.pop() {
                    if matches!(item, OpItem::LParen) {
                        found = true;
                        break;
                    }
                    push_op(&mut output, item)?;
                }
                if !found {
                    return Err("mismatched parentheses".into());
                }
                if let Some(OpItem::Func(_)) = ops.last() {
                    let f = ops.pop().unwrap();
                    push_op(&mut output, f)?;
                }
            }
            Tok::UnaryMinus => ops.push(OpItem::UnaryMinus),
            Tok::Op(c) => {
                let cur = OpItem::Op(*c);
                while let Some(top) = ops.last() {
                    if matches!(top, OpItem::LParen) {
                        break;
                    }
                    let p_top = prec(top);
                    let p_cur = prec(&cur);
                    if p_top > p_cur || (p_top == p_cur && !is_right_assoc(&cur)) {
                        let item = ops.pop().unwrap();
                        push_op(&mut output, item)?;
                    } else {
                        break;
                    }
                }
                ops.push(cur);
            }
        }
    }

    while let Some(item) = ops.pop() {
        if matches!(item, OpItem::LParen) {
            return Err("mismatched parentheses".into());
        }
        push_op(&mut output, item)?;
    }

    let mut stack: Vec<f64> = Vec::new();
    for item in output {
        match item {
            Tok::Num(n) => stack.push(n),
            Tok::UnaryMinus => {
                let a = stack.pop().ok_or("missing operand")?;
                stack.push(-a);
            }
            Tok::Op(c) => {
                let b = stack.pop().ok_or("missing right operand")?;
                let a = stack.pop().ok_or("missing left operand")?;
                let v = match c {
                    '+' => a + b,
                    '-' => a - b,
                    '*' => a * b,
                    '/' => {
                        if b == 0.0 {
                            return Err("division by zero".into());
                        }
                        a / b
                    }
                    '%' => {
                        if b == 0.0 {
                            return Err("modulo by zero".into());
                        }
                        a % b
                    }
                    '^' => a.powf(b),
                    _ => return Err("unknown operator".into()),
                };
                stack.push(v);
            }
            Tok::Ident(f) => {
                let v = match f.as_str() {
                    "sqrt" => stack.pop().ok_or("missing arg")?.sqrt(),
                    "abs" => stack.pop().ok_or("missing arg")?.abs(),
                    "round" => stack.pop().ok_or("missing arg")?.round(),
                    "floor" => stack.pop().ok_or("missing arg")?.floor(),
                    "ceil" => stack.pop().ok_or("missing arg")?.ceil(),
                    "sin" => stack.pop().ok_or("missing arg")?.sin(),
                    "cos" => stack.pop().ok_or("missing arg")?.cos(),
                    "tan" => stack.pop().ok_or("missing arg")?.tan(),
                    "asin" => stack.pop().ok_or("missing arg")?.asin(),
                    "acos" => stack.pop().ok_or("missing arg")?.acos(),
                    "atan" => stack.pop().ok_or("missing arg")?.atan(),
                    "ln" => stack.pop().ok_or("missing arg")?.ln(),
                    "log2" => stack.pop().ok_or("missing arg")?.log2(),
                    "log10" => stack.pop().ok_or("missing arg")?.log10(),
                    "exp" => stack.pop().ok_or("missing arg")?.exp(),
                    "pow" => {
                        let b = stack.pop().ok_or("missing arg 2")?;
                        let a = stack.pop().ok_or("missing arg 1")?;
                        a.powf(b)
                    }
                    "min" => {
                        let b = stack.pop().ok_or("missing arg 2")?;
                        let a = stack.pop().ok_or("missing arg 1")?;
                        a.min(b)
                    }
                    "max" => {
                        let b = stack.pop().ok_or("missing arg 2")?;
                        let a = stack.pop().ok_or("missing arg 1")?;
                        a.max(b)
                    }
                    "clamp" => {
                        let hi = stack.pop().ok_or("missing arg 3 (max)")?;
                        let lo = stack.pop().ok_or("missing arg 2 (min)")?;
                        let x = stack.pop().ok_or("missing arg 1 (value)")?;
                        if lo > hi {
                            return Err("clamp min > max".into());
                        }
                        x.clamp(lo, hi)
                    }
                    _ => return Err(format!("unknown function: {f}")),
                };
                stack.push(v);
            }
            _ => return Err("unexpected token in RPN".into()),
        }
    }

    if stack.len() != 1 {
        return Err("invalid expression".into());
    }
    let res = stack[0];
    if !res.is_finite() {
        return Err("result is not finite".into());
    }
    Ok(res)
}

fn push_op(out: &mut Vec<Tok>, item: OpItem) -> Result<(), String> {
    match item {
        OpItem::Op(c) => out.push(Tok::Op(c)),
        OpItem::UnaryMinus => out.push(Tok::UnaryMinus),
        OpItem::Func(f) => out.push(Tok::Ident(f)),
        OpItem::LParen => return Err("mismatched parentheses".into()),
    }
    Ok(())
}

fn run_calc(args: &Value) -> Result<Value, String> {
    let expr = args
        .get("expression")
        .and_then(Value::as_str)
        .ok_or("missing 'expression' string")?;
    let val = eval_expr(expr)?;
    let formatted = if (val - val.round()).abs() < 1e-12 && val.abs() < 1e15 {
        format!("{}", val.round() as i64)
    } else {
        format!("{val}")
    };
    Ok(json!({ "expression": expr, "value": val, "formatted": formatted }))
}

fn percentile_sorted(sorted: &[f64], p: f64) -> f64 {
    if sorted.len() == 1 {
        return sorted[0];
    }
    let idx = p * (sorted.len() - 1) as f64;
    let lo = idx.floor() as usize;
    let hi = idx.ceil() as usize;
    let frac = idx - lo as f64;
    sorted[lo] * (1.0 - frac) + sorted[hi] * frac
}

fn run_stats(args: &Value) -> Result<Value, String> {
    let arr = args
        .get("numbers")
        .and_then(Value::as_array)
        .ok_or("missing 'numbers' array")?;
    if arr.is_empty() {
        return Err("numbers array must not be empty".into());
    }
    if arr.len() > 100_000 {
        return Err("numbers array too large (max 100000)".into());
    }
    let mut nums = Vec::with_capacity(arr.len());
    for v in arr {
        let n = v.as_f64().ok_or("all elements must be numbers")?;
        if !n.is_finite() {
            return Err("non-finite number".into());
        }
        nums.push(n);
    }
    nums.sort_by(|a, b| a.total_cmp(b));
    let count = nums.len();
    let sum: f64 = nums.iter().sum();
    let mean = sum / count as f64;
    let median = percentile_sorted(&nums, 0.5);
    let p90 = percentile_sorted(&nums, 0.9);
    let p95 = percentile_sorted(&nums, 0.95);
    let pop_var = nums.iter().map(|x| (x - mean).powi(2)).sum::<f64>() / count as f64;
    let sample_var = if count > 1 {
        nums.iter().map(|x| (x - mean).powi(2)).sum::<f64>() / (count - 1) as f64
    } else {
        0.0
    };
    Ok(json!({
        "count": count,
        "sum": sum,
        "mean": mean,
        "median": median,
        "min": nums[0],
        "max": nums[count - 1],
        "range": nums[count - 1] - nums[0],
        "p90": p90,
        "p95": p95,
        "pop_std_dev": pop_var.sqrt(),
        "sample_std_dev": sample_var.sqrt(),
        "sample_variance": sample_var
    }))
}

fn run_unit_convert(args: &Value) -> Result<Value, String> {
    let value = args
        .get("value")
        .and_then(Value::as_f64)
        .ok_or("missing 'value' number")?;
    if !value.is_finite() {
        return Err("non-finite value".into());
    }
    let from = args
        .get("from")
        .and_then(Value::as_str)
        .ok_or("missing 'from'")?;
    let to = args
        .get("to")
        .and_then(Value::as_str)
        .ok_or("missing 'to'")?;

    if let (Some(c), true) = (to_celsius(value, from), is_temp(to)) {
        let out = from_celsius(c, to).unwrap();
        return Ok(json!({ "value": value, "from": from, "to": to, "result": out }));
    }

    let (cat_f, mul_f) = unit_factor(from)?;
    let (cat_t, mul_t) = unit_factor(to)?;
    if cat_f != cat_t {
        return Err(format!("incompatible unit categories: {from} vs {to}"));
    }
    let result = value * mul_f / mul_t;
    Ok(json!({ "value": value, "from": from, "to": to, "result": result }))
}

fn is_temp(u: &str) -> bool {
    matches!(u.to_ascii_uppercase().as_str(), "C" | "F" | "K")
}

fn to_celsius(v: f64, u: &str) -> Option<f64> {
    match u.to_ascii_uppercase().as_str() {
        "C" => Some(v),
        "F" => Some((v - 32.0) * 5.0 / 9.0),
        "K" => Some(v - 273.15),
        _ => None,
    }
}

fn from_celsius(c: f64, u: &str) -> Option<f64> {
    match u.to_ascii_uppercase().as_str() {
        "C" => Some(c),
        "F" => Some(c * 9.0 / 5.0 + 32.0),
        "K" => Some(c + 273.15),
        _ => None,
    }
}

fn unit_factor(u: &str) -> Result<(&'static str, f64), String> {
    match u {
        "mm" => Ok(("len", 0.001)),
        "cm" => Ok(("len", 0.01)),
        "m" => Ok(("len", 1.0)),
        "km" => Ok(("len", 1000.0)),
        "in" => Ok(("len", 0.0254)),
        "ft" => Ok(("len", 0.3048)),
        "mi" => Ok(("len", 1609.344)),
        "m2" => Ok(("area", 1.0)),
        "km2" => Ok(("area", 1e6)),
        "ha" => Ok(("area", 1e4)),
        "sqft" => Ok(("area", 0.09290304)),
        "mg" => Ok(("mass", 1e-6)),
        "g" => Ok(("mass", 0.001)),
        "kg" => Ok(("mass", 1.0)),
        "oz" => Ok(("mass", 0.028349523125)),
        "lb" => Ok(("mass", 0.45359237)),
        "B" => Ok(("data", 1.0)),
        "KB" => Ok(("data", 1000.0)),
        "MB" => Ok(("data", 1e6)),
        "GB" => Ok(("data", 1e9)),
        "TB" => Ok(("data", 1e12)),
        "KiB" => Ok(("data", 1024.0)),
        "MiB" => Ok(("data", 1024.0 * 1024.0)),
        "GiB" => Ok(("data", 1024.0 * 1024.0 * 1024.0)),
        "ms" => Ok(("time", 0.001)),
        "s" => Ok(("time", 1.0)),
        "min" => Ok(("time", 60.0)),
        "h" => Ok(("time", 3600.0)),
        "d" => Ok(("time", 86400.0)),
        "wk" => Ok(("time", 604800.0)),
        "yr" => Ok(("time", 31536000.0)),
        "J" => Ok(("energy", 1.0)),
        "kJ" => Ok(("energy", 1000.0)),
        "Wh" => Ok(("energy", 3600.0)),
        "kWh" => Ok(("energy", 3_600_000.0)),
        "kcal" => Ok(("energy", 4184.0)),
        "Pa" => Ok(("pressure", 1.0)),
        "kPa" => Ok(("pressure", 1000.0)),
        "bar" => Ok(("pressure", 100_000.0)),
        "psi" => Ok(("pressure", 6894.757293168361)),
        "atm" => Ok(("pressure", 101_325.0)),
        _ => Err(format!("unsupported unit: {u}")),
    }
}

// Howard Hinnant's civil calendar algorithms (zero external dependencies)
fn parse_ymd(s: &str) -> Result<(i64, u32, u32), String> {
    let parts: Vec<&str> = s.split('-').collect();
    if parts.len() != 3 {
        return Err(format!("invalid date '{s}', expected YYYY-MM-DD"));
    }
    let y: i64 = parts[0].parse().map_err(|_| "invalid year")?;
    let m: u32 = parts[1].parse().map_err(|_| "invalid month")?;
    let d: u32 = parts[2].parse().map_err(|_| "invalid day")?;
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return Err(format!("date out of range: {s}"));
    }
    let days = days_from_civil(y, m, d);
    if civil_from_days(days) != (y, m, d) {
        return Err(format!("non-existent calendar date: {s}"));
    }
    Ok((y, m, d))
}

fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = y - if m <= 2 { 1 } else { 0 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let m_adj = if m > 2 { m - 3 } else { m + 9 } as i64;
    let doy = (153 * m_adj + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = y + if m <= 2 { 1 } else { 0 };
    (y, m as u32, d as u32)
}

fn weekday_idx(days: i64) -> usize {
    // 1970-01-01 was Thursday (index 4 if Sunday=0)
    ((days + 4).rem_euclid(7)) as usize
}

fn weekday_name(days: i64) -> &'static str {
    [
        "Sunday",
        "Monday",
        "Tuesday",
        "Wednesday",
        "Thursday",
        "Friday",
        "Saturday",
    ][weekday_idx(days)]
}

fn count_workdays_half_open(start_days: i64, end_days: i64) -> i64 {
    if start_days == end_days {
        return 0;
    }
    let sign = if end_days >= start_days { 1 } else { -1 };
    let (lo, hi) = if start_days <= end_days {
        (start_days, end_days)
    } else {
        (end_days, start_days)
    };
    let span = hi - lo;
    let full_weeks = span / 7;
    let rem = span % 7;
    let mut work = full_weeks * 5;
    for i in 0..rem {
        let w = weekday_idx(lo + i);
        if (1..=5).contains(&w) {
            work += 1;
        }
    }
    work * sign
}

fn run_date_calc(args: &Value) -> Result<Value, String> {
    let op = args
        .get("op")
        .and_then(Value::as_str)
        .ok_or("missing 'op'")?;
    let date = args
        .get("date")
        .and_then(Value::as_str)
        .ok_or("missing 'date'")?;
    let (y, m, d) = parse_ymd(date)?;
    let days1 = days_from_civil(y, m, d);
    match op {
        "diff" => {
            let date2 = args
                .get("date2")
                .and_then(Value::as_str)
                .ok_or("missing 'date2'")?;
            let (y2, m2, d2) = parse_ymd(date2)?;
            let days2 = days_from_civil(y2, m2, d2);
            Ok(json!({ "date": date, "date2": date2, "days_between": days2 - days1 }))
        }
        "workdays" => {
            let date2 = args
                .get("date2")
                .and_then(Value::as_str)
                .ok_or("missing 'date2'")?;
            let (y2, m2, d2) = parse_ymd(date2)?;
            let days2 = days_from_civil(y2, m2, d2);
            let workdays = count_workdays_half_open(days1, days2);
            Ok(json!({
                "date": date,
                "date2": date2,
                "days_between": days2 - days1,
                "workdays": workdays
            }))
        }
        "add" => {
            let delta = args
                .get("days")
                .and_then(Value::as_i64)
                .ok_or("missing 'days' integer")?;
            let (ry, rm, rd) = civil_from_days(days1 + delta);
            let res = format!("{ry:04}-{rm:02}-{rd:02}");
            Ok(json!({ "date": date, "days": delta, "result": res, "weekday": weekday_name(days1 + delta) }))
        }
        "weekday" => Ok(json!({ "date": date, "weekday": weekday_name(days1) })),
        _ => Err(format!("unsupported date_calc op: {op}")),
    }
}

fn is_cjk(c: char) -> bool {
    matches!(
        c as u32,
        0x4E00..=0x9FFF | 0x3400..=0x4DBF | 0xF900..=0xFAFF | 0x20000..=0x2A6DF
    )
}

fn run_text_metrics(args: &Value) -> Result<Value, String> {
    let text = args
        .get("text")
        .and_then(Value::as_str)
        .ok_or("missing 'text'")?;
    let chars = text.chars().count();
    let cjk_chars = text.chars().filter(|&c| is_cjk(c)).count();
    let bytes = text.len();
    let words = text.split_whitespace().count();
    let ascii_words = text
        .split(|c: char| !c.is_ascii_alphanumeric())
        .filter(|s| !s.is_empty())
        .count();
    let lines = if text.is_empty() {
        0
    } else {
        text.lines().count()
    };
    let est_tokens = (chars as f64 * 0.6).ceil() as usize;
    Ok(json!({
        "chars": chars,
        "cjk_chars": cjk_chars,
        "bytes": bytes,
        "words": words,
        "ascii_words": ascii_words,
        "lines": lines,
        "estimated_tokens": est_tokens
    }))
}

fn value_type(v: &Value) -> &'static str {
    match v {
        Value::Null => "null",
        Value::Bool(_) => "boolean",
        Value::Number(_) => "number",
        Value::String(_) => "string",
        Value::Array(_) => "array",
        Value::Object(_) => "object",
    }
}

fn run_json_query(args: &Value) -> Result<Value, String> {
    let raw = args
        .get("json_str")
        .and_then(Value::as_str)
        .ok_or("missing 'json_str'")?;
    if raw.len() > 512 * 1024 {
        return Err("json_str exceeds 512 KiB limit".into());
    }
    let parsed: Value = serde_json::from_str(raw).map_err(|e| format!("invalid JSON: {e}"))?;
    let pointer = args.get("pointer").and_then(Value::as_str);
    let op = args
        .get("op")
        .and_then(Value::as_str)
        .unwrap_or(if pointer.is_some() { "query" } else { "validate" });

    match op {
        "validate" => {
            let len = match &parsed {
                Value::Array(a) => Some(a.len()),
                Value::Object(o) => Some(o.len()),
                _ => None,
            };
            Ok(json!({ "valid": true, "type": value_type(&parsed), "length": len }))
        }
        "format" => {
            let formatted = serde_json::to_string_pretty(&parsed).map_err(|e| e.to_string())?;
            Ok(json!({ "valid": true, "formatted": formatted }))
        }
        "keys" => {
            let target = match pointer {
                Some(p) if !p.is_empty() => parsed
                    .pointer(p)
                    .ok_or_else(|| format!("pointer not found: {p}"))?,
                _ => &parsed,
            };
            let keys: Vec<&str> = target
                .as_object()
                .ok_or("target is not a JSON object")?
                .keys()
                .map(String::as_str)
                .collect();
            Ok(json!({ "keys": keys, "count": keys.len() }))
        }
        "query" => {
            let p = pointer.unwrap_or("");
            let target = if p.is_empty() {
                &parsed
            } else {
                parsed
                    .pointer(p)
                    .ok_or_else(|| format!("pointer not found: {p}"))?
            };
            Ok(json!({
                "pointer": p,
                "type": value_type(target),
                "value": target
            }))
        }
        _ => Err(format!("unsupported json_query op: {op}")),
    }
}

fn parse_csv_rows(text: &str, delim: char) -> Result<Vec<Vec<String>>, String> {
    let mut rows = Vec::new();
    let mut row = Vec::new();
    let mut field = String::new();
    let mut in_quotes = false;
    let mut chars = text.chars().peekable();

    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    chars.next();
                    field.push('"');
                } else {
                    in_quotes = false;
                }
            } else {
                field.push(c);
            }
        } else if c == '"' && field.is_empty() {
            in_quotes = true;
        } else if c == delim {
            row.push(std::mem::take(&mut field));
        } else if c == '\r' {
            if chars.peek() == Some(&'\n') {
                chars.next();
            }
            row.push(std::mem::take(&mut field));
            if !(row.len() == 1 && row[0].is_empty()) {
                rows.push(std::mem::take(&mut row));
            } else {
                row.clear();
            }
        } else if c == '\n' {
            row.push(std::mem::take(&mut field));
            if !(row.len() == 1 && row[0].is_empty()) {
                rows.push(std::mem::take(&mut row));
            } else {
                row.clear();
            }
        } else {
            field.push(c);
        }
    }
    if in_quotes {
        return Err("unterminated quoted field in CSV".into());
    }
    if !field.is_empty() || !row.is_empty() {
        row.push(field);
        rows.push(row);
    }
    Ok(rows)
}

fn run_csv_summary(args: &Value) -> Result<Value, String> {
    let text = args
        .get("csv_text")
        .and_then(Value::as_str)
        .ok_or("missing 'csv_text'")?;
    if text.len() > 512 * 1024 {
        return Err("csv_text exceeds 512 KiB limit".into());
    }
    let delim_str = args
        .get("delimiter")
        .and_then(Value::as_str)
        .unwrap_or(",");
    let delim = match delim_str {
        "\\t" => '\t',
        s if s.chars().count() == 1 => s.chars().next().unwrap(),
        _ => return Err("delimiter must be a single character".into()),
    };

    let rows = parse_csv_rows(text, delim)?;
    if rows.is_empty() {
        return Err("CSV is empty".into());
    }
    let headers = &rows[0];
    let data_rows = &rows[1..];
    let mut columns = Vec::with_capacity(headers.len());

    for (col_idx, name) in headers.iter().enumerate() {
        let mut non_empty = 0usize;
        let mut nums = Vec::new();
        for r in data_rows {
            if let Some(cell) = r.get(col_idx) {
                let t = cell.trim();
                if !t.is_empty() {
                    non_empty += 1;
                    if let Ok(v) = t.parse::<f64>() {
                        if v.is_finite() {
                            nums.push(v);
                        }
                    }
                }
            }
        }
        let mut col = json!({
            "name": name,
            "non_empty": non_empty,
            "numeric_count": nums.len()
        });
        if !nums.is_empty() {
            let sum: f64 = nums.iter().sum();
            let min = nums.iter().copied().fold(f64::INFINITY, f64::min);
            let max = nums.iter().copied().fold(f64::NEG_INFINITY, f64::max);
            col["sum"] = json!(sum);
            col["min"] = json!(min);
            col["max"] = json!(max);
            col["mean"] = json!(sum / nums.len() as f64);
        }
        columns.push(col);
    }

    Ok(json!({
        "row_count": data_rows.len(),
        "column_count": headers.len(),
        "headers": headers,
        "columns": columns
    }))
}

fn run_text_diff(args: &Value) -> Result<Value, String> {
    let old_text = args
        .get("old_text")
        .and_then(Value::as_str)
        .ok_or("missing 'old_text'")?;
    let new_text = args
        .get("new_text")
        .and_then(Value::as_str)
        .ok_or("missing 'new_text'")?;
    let a: Vec<&str> = old_text.lines().collect();
    let b: Vec<&str> = new_text.lines().collect();
    if a.len() > 400 || b.len() > 400 {
        return Err("text_diff supports up to 400 lines per input".into());
    }

    let n = a.len();
    let m = b.len();
    let mut dp = vec![vec![0u32; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            dp[i][j] = if a[i] == b[j] {
                dp[i + 1][j + 1] + 1
            } else {
                dp[i + 1][j].max(dp[i][j + 1])
            };
        }
    }

    let mut diff_lines = Vec::new();
    let (mut i, mut j) = (0, 0);
    let (mut added, mut removed, mut unchanged) = (0usize, 0usize, 0usize);
    while i < n && j < m {
        if a[i] == b[j] {
            diff_lines.push(format!("  {}", a[i]));
            unchanged += 1;
            i += 1;
            j += 1;
        } else if dp[i + 1][j] >= dp[i][j + 1] {
            diff_lines.push(format!("- {}", a[i]));
            removed += 1;
            i += 1;
        } else {
            diff_lines.push(format!("+ {}", b[j]));
            added += 1;
            j += 1;
        }
    }
    while i < n {
        diff_lines.push(format!("- {}", a[i]));
        removed += 1;
        i += 1;
    }
    while j < m {
        diff_lines.push(format!("+ {}", b[j]));
        added += 1;
        j += 1;
    }

    Ok(json!({
        "added": added,
        "removed": removed,
        "unchanged": unchanged,
        "diff": diff_lines.join("\n")
    }))
}

fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = chunk.get(1).copied().unwrap_or(0) as u32;
        let b2 = chunk.get(2).copied().unwrap_or(0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[((triple >> 18) & 0x3F) as usize] as char);
        out.push(TABLE[((triple >> 12) & 0x3F) as usize] as char);
        out.push(if chunk.len() > 1 {
            TABLE[((triple >> 6) & 0x3F) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            TABLE[(triple & 0x3F) as usize] as char
        } else {
            '='
        });
    }
    out
}

fn base64_decode(input: &str) -> Result<Vec<u8>, String> {
    let clean: Vec<u8> = input
        .bytes()
        .filter(|b| !b.is_ascii_whitespace())
        .collect();
    if clean.len() % 4 != 0 {
        return Err("invalid base64 length".into());
    }
    let val = |b: u8| -> Result<u32, String> {
        match b {
            b'A'..=b'Z' => Ok((b - b'A') as u32),
            b'a'..=b'z' => Ok((b - b'a' + 26) as u32),
            b'0'..=b'9' => Ok((b - b'0' + 52) as u32),
            b'+' => Ok(62),
            b'/' => Ok(63),
            _ => Err(format!("invalid base64 byte: {b}")),
        }
    };
    let mut out = Vec::with_capacity(clean.len() / 4 * 3);
    for chunk in clean.chunks_exact(4) {
        let a = val(chunk[0])?;
        let b = val(chunk[1])?;
        let c = if chunk[2] == b'=' { 0 } else { val(chunk[2])? };
        let d = if chunk[3] == b'=' { 0 } else { val(chunk[3])? };
        let triple = (a << 18) | (b << 12) | (c << 6) | d;
        out.push(((triple >> 16) & 0xFF) as u8);
        if chunk[2] != b'=' {
            out.push(((triple >> 8) & 0xFF) as u8);
        }
        if chunk[3] != b'=' {
            out.push((triple & 0xFF) as u8);
        }
    }
    Ok(out)
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0F) as usize] as char);
    }
    out
}

fn hex_decode(s: &str) -> Result<Vec<u8>, String> {
    let clean: Vec<u8> = s.bytes().filter(|b| !b.is_ascii_whitespace()).collect();
    if clean.len() % 2 != 0 {
        return Err("invalid hex length".into());
    }
    let nibble = |b: u8| -> Result<u8, String> {
        match b {
            b'0'..=b'9' => Ok(b - b'0'),
            b'a'..=b'f' => Ok(b - b'a' + 10),
            b'A'..=b'F' => Ok(b - b'A' + 10),
            _ => Err("invalid hex character".into()),
        }
    };
    let mut out = Vec::with_capacity(clean.len() / 2);
    for pair in clean.chunks_exact(2) {
        out.push((nibble(pair[0])? << 4) | nibble(pair[1])?);
    }
    Ok(out)
}

fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn url_decode(s: &str) -> Result<String, String> {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).map_err(|_| "invalid utf8")?;
            let v = u8::from_str_radix(hex, 16).map_err(|_| "invalid percent encoding")?;
            out.push(v);
            i += 3;
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(|_| "decoded bytes are not valid UTF-8".into())
}

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

fn fnv1a_64(data: &[u8]) -> u64 {
    let mut h = 0xCBF2_9CE4_8422_2325u64;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x0100_0000_01B3);
    }
    h
}

fn sha256(data: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let bit_len = (data.len() as u64).wrapping_mul(8);
    let mut msg = data.to_vec();
    msg.push(0x80);
    while (msg.len() % 64) != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());

    for block in msg.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes(block[i * 4..i * 4 + 4].try_into().unwrap());
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }
        for (slot, delta) in h.iter_mut().zip([a, b, c, d, e, f, g, hh]) {
            *slot = slot.wrapping_add(delta);
        }
    }
    let mut digest = [0u8; 32];
    for (i, word) in h.iter().enumerate() {
        digest[i * 4..i * 4 + 4].copy_from_slice(&word.to_be_bytes());
    }
    digest
}

fn run_encode_hash(args: &Value) -> Result<Value, String> {
    let op = args
        .get("op")
        .and_then(Value::as_str)
        .ok_or("missing 'op'")?;
    let input = args
        .get("input")
        .and_then(Value::as_str)
        .ok_or("missing 'input'")?;
    if input.len() > 512 * 1024 {
        return Err("input exceeds 512 KiB limit".into());
    }
    let result = match op {
        "base64_encode" => base64_encode(input.as_bytes()),
        "base64_decode" => {
            let bytes = base64_decode(input)?;
            String::from_utf8(bytes).map_err(|_| "decoded bytes are not valid UTF-8".to_owned())?
        }
        "hex_encode" => hex_encode(input.as_bytes()),
        "hex_decode" => {
            let bytes = hex_decode(input)?;
            String::from_utf8(bytes).map_err(|_| "decoded bytes are not valid UTF-8".to_owned())?
        }
        "url_encode" => url_encode(input),
        "url_decode" => url_decode(input)?,
        "crc32" => format!("{:08x}", crc32(input.as_bytes())),
        "fnv1a_64" => format!("{:016x}", fnv1a_64(input.as_bytes())),
        "sha256" => hex_encode(&sha256(input.as_bytes())),
        _ => return Err(format!("unsupported encode_hash op: {op}")),
    };
    Ok(json!({ "op": op, "result": result }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_cfg() -> AppConfig {
        AppConfig::from_env()
    }

    #[test]
    fn calc_precedence_and_funcs() {
        assert!((eval_expr("2 + 3 * 4").unwrap() - 14.0).abs() < 1e-9);
        assert!((eval_expr("(2 + 3) * 4^2").unwrap() - 80.0).abs() < 1e-9);
        assert!((eval_expr("-5 + sqrt(144) + max(3, 7)").unwrap() - 14.0).abs() < 1e-9);
        assert!((eval_expr("clamp(15, 0, 10) + log2(8)").unwrap() - 13.0).abs() < 1e-9);
        assert!(eval_expr("1 / 0").is_err());
    }

    #[test]
    fn date_math_roundtrip_and_workdays() {
        let cfg = test_cfg();
        let out = execute_tool(
            "date_calc",
            r#"{"op":"add","date":"2026-11-17","days":14}"#,
            &cfg,
            false,
        );
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["result"], "2026-12-01");
        assert_eq!(v["weekday"], "Tuesday");

        let wd = execute_tool(
            "date_calc",
            r#"{"op":"workdays","date":"2026-11-16","date2":"2026-11-23"}"#,
            &cfg,
            false,
        );
        let wv: Value = serde_json::from_str(&wd).unwrap();
        assert_eq!(wv["workdays"], 5);
    }

    #[test]
    fn unit_conversion() {
        let cfg = test_cfg();
        let out = execute_tool("unit_convert", r#"{"value":100,"from":"C","to":"F"}"#, &cfg, false);
        let v: Value = serde_json::from_str(&out).unwrap();
        assert!((v["result"].as_f64().unwrap() - 212.0).abs() < 1e-9);

        let e = execute_tool("unit_convert", r#"{"value":2,"from":"kWh","to":"kJ"}"#, &cfg, false);
        let ev: Value = serde_json::from_str(&e).unwrap();
        assert!((ev["result"].as_f64().unwrap() - 7200.0).abs() < 1e-9);
    }

    #[test]
    fn json_csv_diff_hash_tools() {
        let cfg = test_cfg();
        let jq = execute_tool(
            "json_query",
            r#"{"json_str":"{\"items\":[{\"name\":\"a\",\"price\":12}]}","pointer":"/items/0/price"}"#,
            &cfg,
            false,
        );
        let jv: Value = serde_json::from_str(&jq).unwrap();
        assert_eq!(jv["value"], 12);

        let csv = execute_tool(
            "csv_summary",
            r#"{"csv_text":"item,cost\n\"a,b\",10\nc,20"}"#,
            &cfg,
            false,
        );
        let cv: Value = serde_json::from_str(&csv).unwrap();
        assert_eq!(cv["row_count"], 2);
        assert_eq!(cv["columns"][1]["sum"], 30.0);

        let diff = execute_tool(
            "text_diff",
            r#"{"old_text":"a\nb\nc","new_text":"a\nx\nc\nd"}"#,
            &cfg,
            false,
        );
        let dv: Value = serde_json::from_str(&diff).unwrap();
        assert_eq!(dv["added"], 2);
        assert_eq!(dv["removed"], 1);
        assert_eq!(dv["unchanged"], 2);

        let hash = execute_tool("encode_hash", r#"{"op":"sha256","input":"abc"}"#, &cfg, false);
        let hv: Value = serde_json::from_str(&hash).unwrap();
        assert_eq!(
            hv["result"],
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn device_tool_permission_and_catalog() {
        let cfg = test_cfg();
        let blocked = execute_tool("shell_exec", r#"{"command":"echo hi"}"#, &cfg, false);
        let bv: Value = serde_json::from_str(&blocked).unwrap();
        assert!(bv["error"].as_str().unwrap().contains("device_tools"));

        let safe_catalog = tool_definitions(false);
        let full_catalog = tool_definitions(true);
        assert!(full_catalog.as_array().unwrap().len() > safe_catalog.as_array().unwrap().len());
    }
}
