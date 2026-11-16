use serde_json::{json, Value};

pub fn tool_definitions() -> Value {
    json!([
        {
            "type": "function",
            "function": {
                "name": "calc",
                "description": "Evaluate a mathematical expression accurately using local deterministic arithmetic. Supports +, -, *, /, %, ^, parentheses, constants (pi, e), and functions (sqrt, abs, round, floor, ceil, sin, cos, tan, ln, log10, pow, min, max).",
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
                "description": "Compute descriptive statistics (count, sum, mean, median, min, max, sample_std_dev, pop_std_dev) for a list of numbers.",
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
                "description": "Convert values between common units of length (m, km, cm, mm, mi, ft, in), mass (kg, g, mg, lb, oz), temperature (C, F, K), data (B, KB, MB, GB, TB, KiB, MiB, GiB), and time (s, min, h, d).",
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
                "description": "Perform Gregorian calendar date calculations: 'diff' (days between date and date2), 'add' (add days to date), or 'weekday' (day of week for date). Dates must be YYYY-MM-DD.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "op": {
                            "type": "string",
                            "enum": ["diff", "add", "weekday"]
                        },
                        "date": {
                            "type": "string",
                            "description": "Primary date in YYYY-MM-DD format"
                        },
                        "date2": {
                            "type": "string",
                            "description": "Second date in YYYY-MM-DD format (required for 'diff')"
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
                "description": "Count Unicode characters, UTF-8 bytes, whitespace-delimited words, lines, and rough token estimate of a text.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "text": { "type": "string" }
                    },
                    "required": ["text"]
                }
            }
        }
    ])
}

pub fn execute_tool(name: &str, args_json: &str) -> String {
    let args: Value = match serde_json::from_str(args_json) {
        Ok(v) => v,
        Err(e) => return json!({ "error": format!("invalid JSON arguments: {e}") }).to_string(),
    };
    let out = match name {
        "calc" => run_calc(&args),
        "stats" => run_stats(&args),
        "unit_convert" => run_unit_convert(&args),
        "date_calc" => run_date_calc(&args),
        "text_metrics" => run_text_metrics(&args),
        _ => Err(format!("unknown local tool: {name}")),
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
                    "ln" => stack.pop().ok_or("missing arg")?.ln(),
                    "log10" => stack.pop().ok_or("missing arg")?.log10(),
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

fn run_stats(args: &Value) -> Result<Value, String> {
    let arr = args
        .get("numbers")
        .and_then(Value::as_array)
        .ok_or("missing 'numbers' array")?;
    if arr.is_empty() {
        return Err("numbers array must not be empty".into());
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
    let median = if count % 2 == 1 {
        nums[count / 2]
    } else {
        (nums[count / 2 - 1] + nums[count / 2]) / 2.0
    };
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
        "pop_std_dev": pop_var.sqrt(),
        "sample_std_dev": sample_var.sqrt()
    }))
}

fn run_unit_convert(args: &Value) -> Result<Value, String> {
    let value = args
        .get("value")
        .and_then(Value::as_f64)
        .ok_or("missing 'value' number")?;
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
        "s" => Ok(("time", 1.0)),
        "min" => Ok(("time", 60.0)),
        "h" => Ok(("time", 3600.0)),
        "d" => Ok(("time", 86400.0)),
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

fn weekday_name(days: i64) -> &'static str {
    // 1970-01-01 was Thursday (index 4 if Sunday=0)
    let w = ((days + 4).rem_euclid(7)) as usize;
    [
        "Sunday",
        "Monday",
        "Tuesday",
        "Wednesday",
        "Thursday",
        "Friday",
        "Saturday",
    ][w]
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

fn run_text_metrics(args: &Value) -> Result<Value, String> {
    let text = args
        .get("text")
        .and_then(Value::as_str)
        .ok_or("missing 'text'")?;
    let chars = text.chars().count();
    let bytes = text.len();
    let words = text.split_whitespace().count();
    let lines = if text.is_empty() {
        0
    } else {
        text.lines().count()
    };
    let est_tokens = (chars as f64 * 0.6).ceil() as usize;
    Ok(json!({
        "chars": chars,
        "bytes": bytes,
        "words": words,
        "lines": lines,
        "estimated_tokens": est_tokens
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn calc_precedence_and_funcs() {
        assert!((eval_expr("2 + 3 * 4").unwrap() - 14.0).abs() < 1e-9);
        assert!((eval_expr("(2 + 3) * 4^2").unwrap() - 80.0).abs() < 1e-9);
        assert!((eval_expr("-5 + sqrt(144) + max(3, 7)").unwrap() - 14.0).abs() < 1e-9);
        assert!(eval_expr("1 / 0").is_err());
    }

    #[test]
    fn date_math_roundtrip() {
        let out = execute_tool(
            "date_calc",
            r#"{"op":"add","date":"2026-11-17","days":14}"#,
        );
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["result"], "2026-12-01");
        assert_eq!(v["weekday"], "Tuesday");
    }

    #[test]
    fn unit_conversion() {
        let out = execute_tool("unit_convert", r#"{"value":100,"from":"C","to":"F"}"#);
        let v: Value = serde_json::from_str(&out).unwrap();
        assert!((v["result"].as_f64().unwrap() - 212.0).abs() < 1e-9);
    }
}
