// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The remote-control API as its OpenAPI document, `openapi.yaml`, describes it: the paths, their operations and what
//! each takes. Every call is checked against it before it is answered, so that the document holds what the API does.

use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::LazyLock;

use jsonschema::{Draft, Registry, Validator};
use percent_encoding::{AsciiSet, CONTROLS, utf8_percent_encode};
use serde_json::{Map, Number, Value, json};
use yaml_rust2::{Yaml, YamlLoader};

/// The document njMule ships, which the Java engine serves too.
pub const DOCUMENT: &str = include_str!("../../../njmule-core/src/main/resources/org/njmule/core/remote/openapi.yaml");
/// The largest request body taken.
pub const MAX_BODY: usize = 1024 * 1024;
// The document's own address, against which its references resolve; nothing is fetched from it.
const BASE: &str = "https://njmule.invalid/api/v1/openapi.json";
const METHODS: [&str; 9] = ["get", "put", "post", "delete", "options", "head", "patch", "trace", "query"];
const MAX_ERRORS: usize = 3;
// What a body may hold at most: nesting, and the length of a name and of a number.
const MAX_DEPTH: usize = 8;
const MAX_NAME: usize = 64;
const MAX_NUMBER: usize = 20;

static SHIPPED: LazyLock<Result<ApiSpecification, String>> = LazyLock::new(|| ApiSpecification::parse(DOCUMENT));

#[derive(Debug)]
pub struct ApiSpecification {
    document: Value,
    routes: Vec<Route>,
}

/// A path of the document and its operations by HTTP method.
#[derive(Debug)]
pub struct Route {
    pub path: String,
    template: Vec<String>,
    operations: BTreeMap<String, Operation>,
}

/// An operation of the document: what it takes and how to check it.
#[derive(Debug)]
pub struct Operation {
    pub id: String,
    pub method: String,
    pub path: String,
    parameters: Vec<Parameter>,
    body: Option<Body>,
}

#[derive(Debug)]
struct Parameter {
    name: String,
    in_path: bool,
    required: bool,
    kind: Kind,
    schema: Validator,
}

/// How the text of a parameter becomes the JSON value its schema checks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Text,
    Integer,
    // A list apart by commas: style form without explode.
    Texts,
}

#[derive(Debug)]
struct Body {
    required: bool,
    schema: Validator,
}

impl ApiSpecification {
    /// The document shipped with njMule; why not when it cannot be read, which its tests rule out.
    pub fn shipped() -> Result<&'static Self, &'static str> {
        SHIPPED.as_ref().map_err(String::as_str)
    }

    /// The document whose text is `yaml`; why not when it asks for what nothing here would check.
    pub fn parse(yaml: &str) -> Result<Self, String> {
        let loaded = YamlLoader::load_from_str(yaml).map_err(|e| invalid(&format!("not YAML: {e}")))?;
        let document = json_of(loaded.first().unwrap_or(&Yaml::Null))?;
        let registry = Registry::new()
            .add(BASE, document.clone())
            .and_then(|registry| registry.prepare())
            .map_err(|e| invalid(&e.to_string()))?;
        let reader = Reader { document: &document, registry: &registry };
        let mut routes = Vec::new();
        if let Some(paths) = document.get("paths").and_then(Value::as_object) {
            for (path, item) in paths {
                routes.push(reader.route(path, item)?);
            }
        }
        routes.sort_by(concrete_first);
        let mut ids = HashSet::new();
        for operation in routes.iter().flat_map(|route| route.operations.values()) {
            if !ids.insert(operation.id.clone()) {
                return Err(invalid(&format!("operationId {} is used twice", operation.id)));
            }
        }
        Ok(Self { document, routes })
    }

    /// The document read as JSON.
    pub fn document(&self) -> &Value {
        &self.document
    }

    /// The operations of the document by their ids.
    pub fn operations(&self) -> BTreeMap<&str, &Operation> {
        self.routes
            .iter()
            .flat_map(|route| route.operations.values())
            .map(|operation| (operation.id.as_str(), operation))
            .collect()
    }

    /// The path of the document that the segments of a request's path match, concrete paths before templated ones.
    pub fn route(&self, segments: &[String]) -> Option<&Route> {
        self.routes.iter().find(|route| route.matches(segments))
    }
}

impl Route {
    fn matches(&self, segments: &[String]) -> bool {
        segments.len() == self.template.len()
            && self
                .template
                .iter()
                .zip(segments)
                .all(|(template, segment)| is_variable(template) || template == segment)
    }

    /// The values of the path's variables in the segments of a request it matches.
    pub fn values(&self, segments: &[String]) -> HashMap<String, String> {
        self.template
            .iter()
            .zip(segments)
            .filter(|(template, _)| is_variable(template))
            .map(|(template, segment)| {
                (template.trim_start_matches('{').trim_end_matches('}').to_owned(), segment.clone())
            })
            .collect()
    }

    pub fn operation(&self, method: &str) -> Option<&Operation> {
        self.operations.get(&method.to_lowercase())
    }
}

impl Operation {
    /// Checks the parameters of a request against their schemas, read as the schemas type them; an optional one left
    /// out is absent.
    pub fn check_arguments(
        &self,
        path_values: &HashMap<String, String>,
        query: &HashMap<String, String>,
    ) -> Result<(), String> {
        for parameter in &self.parameters {
            let given = if parameter.in_path { path_values.get(&parameter.name) } else { query.get(&parameter.name) };
            let Some(given) = given else {
                if parameter.required {
                    return Err(format!("Missing parameter: {}", parameter.name));
                }
                continue;
            };
            let value = parameter.read(given)?;
            check(&parameter.schema, &value, &format!("Parameter {}", parameter.name))?;
        }
        Ok(())
    }

    /// Whether the operation takes a body.
    pub fn takes_body(&self) -> bool {
        self.body.is_some()
    }

    /// Checks the body of a request against its schema; one left out where it may be is taken as an empty object.
    pub fn check_body(&self, bytes: &[u8]) -> Result<(), String> {
        let Some(spec) = &self.body else {
            return Ok(());
        };
        if bytes.iter().all(u8::is_ascii_whitespace) {
            return if spec.required { Err("The request needs a JSON body.".to_owned()) } else { Ok(()) };
        }
        let body: Value = serde_json::from_slice(bytes).map_err(|e| format!("Malformed JSON: {e}"))?;
        within_limits(&body, 0)?;
        check(&spec.schema, &body, "Body")
    }
}

impl Parameter {
    fn read(&self, text: &str) -> Result<Value, String> {
        match self.kind {
            Kind::Text => Ok(Value::String(text.to_owned())),
            Kind::Integer => text
                .trim()
                .parse::<i64>()
                .map(Value::from)
                .map_err(|_| format!("Parameter {} must be an integer.", self.name)),
            Kind::Texts => Ok(Value::Array(
                text.split(',').map(str::trim).filter(|item| !item.is_empty()).map(Value::from).collect(),
            )),
        }
    }
}

fn check(schema: &Validator, value: &Value, what: &str) -> Result<(), String> {
    let errors: Vec<String> = schema
        .iter_errors(value)
        .take(MAX_ERRORS)
        .map(|error| {
            let at = error.instance_path().to_string();
            if at.is_empty() { error.to_string() } else { format!("{at}: {error}") }
        })
        .collect();
    if errors.is_empty() { Ok(()) } else { Err(format!("{what} does not follow the API: {}", errors.join("; "))) }
}

// A body as large as JSON parsers usually take is still too deep or too long in its names and numbers for the API.
fn within_limits(value: &Value, depth: usize) -> Result<(), String> {
    if depth > MAX_DEPTH {
        return Err(format!("Malformed JSON: nested deeper than {MAX_DEPTH}"));
    }
    match value {
        Value::Object(fields) => {
            for (name, field) in fields {
                if name.chars().count() > MAX_NAME {
                    return Err(format!("Malformed JSON: a name longer than {MAX_NAME}"));
                }
                within_limits(field, depth.saturating_add(1))?;
            }
            Ok(())
        }
        Value::Array(items) => items.iter().try_for_each(|item| within_limits(item, depth.saturating_add(1))),
        Value::Number(number) if number.to_string().len() > MAX_NUMBER => {
            Err(format!("Malformed JSON: a number longer than {MAX_NUMBER}"))
        }
        _ => Ok(()),
    }
}

// OpenAPI matches concrete paths before templated ones: /servers/connect before /servers/{address}.
fn concrete_first(a: &Route, b: &Route) -> Ordering {
    for (left, right) in a.template.iter().zip(&b.template) {
        let by_kind = is_variable(left).cmp(&is_variable(right));
        if by_kind != Ordering::Equal {
            return by_kind;
        }
    }
    a.template.len().cmp(&b.template.len())
}

fn is_variable(segment: &str) -> bool {
    segment.starts_with('{') && segment.ends_with('}')
}

fn invalid(why: &str) -> String {
    format!("openapi.yaml: {why}")
}

// Reads the parts of the document this module supports, and fails on any other, so that the document cannot ask for
// what nothing would check.
struct Reader<'a> {
    document: &'a Value,
    registry: &'a Registry<'a>,
}

impl<'a> Reader<'a> {
    fn route(&self, path: &str, item: &'a Value) -> Result<Route, String> {
        if !path.starts_with('/') {
            return Err(invalid(&format!("path {path} must start with /")));
        }
        let template = path.split('/').filter(|segment| !segment.is_empty()).map(str::to_owned).collect();
        let at = format!("/paths/{}", escape(path));
        let shared = self.parameters(item.get("parameters"), &format!("{at}/parameters"))?;
        let mut operations = BTreeMap::new();
        if let Some(item) = item.as_object() {
            for (method, operation) in item {
                if METHODS.contains(&method.as_str()) {
                    let found = self.operation(path, method, operation, &format!("{at}/{method}"), &shared)?;
                    operations.insert(method.clone(), found);
                }
            }
        }
        Ok(Route { path: path.to_owned(), template, operations })
    }

    fn operation(
        &self,
        path: &str,
        method: &str,
        operation: &'a Value,
        at: &str,
        shared: &[Located<'a>],
    ) -> Result<Operation, String> {
        let id = operation.get("operationId").and_then(Value::as_str).unwrap_or_default().to_owned();
        if id.is_empty() {
            return Err(invalid(&format!("{method} {path} has no operationId")));
        }
        let mut by_name: Vec<Located<'a>> = Vec::new();
        for located in
            shared.iter().cloned().chain(self.parameters(operation.get("parameters"), &format!("{at}/parameters"))?)
        {
            by_name.retain(|known| (known.within(), known.name()) != (located.within(), located.name()));
            by_name.push(located);
        }
        let parameters = by_name.iter().map(|located| self.parameter(&id, located)).collect::<Result<Vec<_>, _>>()?;
        let body = match operation.get("requestBody") {
            Some(request) => {
                let request = self.resolve(request, &format!("{at}/requestBody"))?;
                let content = request.node.get("content").and_then(Value::as_object);
                if content.is_none_or(|content| content.len() != 1 || !content.contains_key("application/json")) {
                    return Err(invalid(&format!("{id} must take application/json only")));
                }
                let required = request.node.get("required").and_then(Value::as_bool).unwrap_or(false);
                let schema = self.schema(&format!("{}/content/application~1json/schema", request.at))?;
                Some(Body { required, schema })
            }
            None => None,
        };
        Ok(Operation { id, method: method.to_owned(), path: path.to_owned(), parameters, body })
    }

    fn parameters(&self, list: Option<&'a Value>, at: &str) -> Result<Vec<Located<'a>>, String> {
        let Some(list) = list.and_then(Value::as_array) else {
            return Ok(Vec::new());
        };
        list.iter()
            .enumerate()
            .map(|(index, parameter)| self.resolve(parameter, &format!("{at}/{index}")))
            .collect()
    }

    fn parameter(&self, operation: &str, located: &Located<'_>) -> Result<Parameter, String> {
        let within = located.within();
        if within != "path" && within != "query" {
            return Err(invalid(&format!("{operation}: parameters in {within} are not supported")));
        }
        let schema_at = format!("{}/schema", located.at);
        let schema = self.resolve(located.node.get("schema").unwrap_or(&Value::Null), &schema_at)?;
        let kind = match schema.node.get("type").and_then(Value::as_str).unwrap_or("string") {
            "string" => Kind::Text,
            "integer" => Kind::Integer,
            "array" => {
                let form = located.node.get("style").and_then(Value::as_str).unwrap_or("form") == "form";
                let explode = located.node.get("explode").and_then(Value::as_bool).unwrap_or(true);
                let texts = schema.node.pointer("/items/type").and_then(Value::as_str) == Some("string");
                if !form || explode || !texts {
                    return Err(invalid(&format!(
                        "{operation}: {} must be strings in a form without explode",
                        located.name()
                    )));
                }
                Kind::Texts
            }
            other => return Err(invalid(&format!("{operation}: parameters of type {other} are not supported"))),
        };
        let required = within == "path" || located.node.get("required").and_then(Value::as_bool).unwrap_or(false);
        Ok(Parameter {
            name: located.name().to_owned(),
            in_path: within == "path",
            required,
            kind,
            schema: self.schema(&schema_at)?,
        })
    }

    // A node, or the one its $ref names, with where it is.
    fn resolve(&self, node: &'a Value, at: &str) -> Result<Located<'a>, String> {
        let Some(target) = node.get("$ref").and_then(Value::as_str) else {
            return Ok(Located { node, at: at.to_owned() });
        };
        let Some(pointer) = target.strip_prefix('#') else {
            return Err(invalid(&format!("only references inside the document are supported: {target}")));
        };
        let resolved =
            self.document.pointer(pointer).ok_or_else(|| invalid(&format!("unresolved reference {target}")))?;
        self.resolve(resolved, pointer)
    }

    fn schema(&self, at: &str) -> Result<Validator, String> {
        jsonschema::options()
            .with_draft(Draft::Draft202012)
            .with_registry(self.registry)
            .build(&json!({"$ref": format!("{BASE}#{}", utf8_percent_encode(at, FRAGMENT))}))
            .map_err(|e| invalid(&format!("the schema at {at}: {e}")))
    }
}

#[derive(Clone)]
struct Located<'a> {
    node: &'a Value,
    at: String,
}

impl Located<'_> {
    fn name(&self) -> &str {
        self.node.get("name").and_then(Value::as_str).unwrap_or_default()
    }

    fn within(&self) -> &str {
        self.node.get("in").and_then(Value::as_str).unwrap_or_default()
    }
}

// What a JSON pointer may not hold as it is in the fragment of a URI, such as the braces of templated paths.
const FRAGMENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'<')
    .add(b'>')
    .add(b'[')
    .add(b'\\')
    .add(b']')
    .add(b'^')
    .add(b'`')
    .add(b'{')
    .add(b'|')
    .add(b'}');

// A token of a JSON pointer.
fn escape(token: &str) -> String {
    token.replace('~', "~0").replace('/', "~1")
}

// The JSON that the YAML of the document reads as.
fn json_of(yaml: &Yaml) -> Result<Value, String> {
    Ok(match yaml {
        Yaml::Real(text) => text
            .parse::<f64>()
            .ok()
            .and_then(Number::from_f64)
            .map(Value::Number)
            .ok_or_else(|| invalid(&format!("not a number: {text}")))?,
        Yaml::Integer(number) => Value::from(*number),
        Yaml::String(text) => Value::String(text.clone()),
        Yaml::Boolean(value) => Value::Bool(*value),
        Yaml::Array(items) => Value::Array(items.iter().map(json_of).collect::<Result<_, _>>()?),
        Yaml::Hash(entries) => {
            let mut fields = Map::new();
            for (key, value) in entries {
                let key = match key {
                    Yaml::String(text) | Yaml::Real(text) => text.clone(),
                    Yaml::Integer(number) => number.to_string(),
                    Yaml::Boolean(value) => value.to_string(),
                    _ => return Err(invalid("a mapping key that is no scalar")),
                };
                fields.insert(key, json_of(value)?);
            }
            Value::Object(fields)
        }
        Yaml::Null => Value::Null,
        Yaml::Alias(_) | Yaml::BadValue => return Err(invalid("aliases are not supported")),
    })
}
