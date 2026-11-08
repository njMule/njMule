// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ApiContract: checks the answers and events of the API against the response schemas of its OpenAPI
//! document, strictly: every field an answer holds must be in the document, although the document itself lets clients
//! take fields it does not name.

#![allow(clippy::unwrap_used, clippy::panic)]

use std::collections::HashSet;
use std::sync::LazyLock;

use jsonschema::{Draft, Registry, Validator};
use njmule_engine::remote::api_specification::ApiSpecification;
use njmule_engine::remote::remote_api;
use percent_encoding::{AsciiSet, CONTROLS, percent_decode_str, utf8_percent_encode};
use serde_json::{Map, Value, json};

const BASE: &str = "https://njmule.invalid/test/openapi.json";
const ERROR: &str = "/components/responses/Error";
const EVENTS: &str = "/components/schemas/EngineEvent";

static CONTRACT: LazyLock<ApiContract> = LazyLock::new(ApiContract::new);

pub struct ApiContract {
    document: Value,
    registry: Registry<'static>,
}

/// The contract of the document njMule ships.
pub fn shipped() -> &'static ApiContract {
    &CONTRACT
}

impl ApiContract {
    fn new() -> Self {
        let document = strict(ApiSpecification::shipped().unwrap().document());
        let registry = Registry::new().add(BASE, document.clone()).unwrap().prepare().unwrap();
        Self { document, registry }
    }

    /// What in an answer does not follow the response the document gives for its status (or its default response);
    /// empty when it follows it.
    pub fn answer_violations(
        &self,
        method: &str,
        path_and_query: &str,
        status: u16,
        content_type: &str,
        body: &str,
    ) -> Vec<String> {
        let path = path_and_query.split_once('?').map_or(path_and_query, |(path, _)| path);
        let inside = path.strip_prefix(remote_api::PREFIX).unwrap_or(path);
        let segments: Vec<String> = inside
            .split('/')
            .filter(|segment| !segment.is_empty())
            .map(|segment| percent_decode_str(segment).decode_utf8_lossy().into_owned())
            .collect();
        let specification = ApiSpecification::shipped().unwrap();
        let Some(route) = specification.route(&segments).filter(|route| route.operation(method).is_some()) else {
            // No operation of the document: the answer is the document's error.
            return self.violations(&self.resolve(ERROR), status, content_type, body);
        };
        let at = format!("/paths/{}/{}/responses", escape(&route.path), method.to_lowercase());
        let responses = self.document.pointer(&at).unwrap_or(&Value::Null);
        let code = if responses.get(status.to_string()).is_some() { status.to_string() } else { "default".to_owned() };
        if responses.get(&code).is_none() {
            return vec![format!("the document gives no answer {status} to {method} {path}")];
        }
        self.violations(&self.resolve(&format!("{at}/{code}")), status, content_type, body)
    }

    fn violations(&self, response: &str, status: u16, content_type: &str, body: &str) -> Vec<String> {
        let kind = content_type.split(';').next().unwrap_or_default().trim();
        let at = format!("{response}/content/{}", escape(kind));
        if self.document.pointer(&at).is_none() {
            return vec![format!("the document gives no {kind} answer {status}")];
        }
        if kind != "application/json" {
            return Vec::new();
        }
        match serde_json::from_str(body) {
            Ok(answer) => messages(&self.schema(&format!("{at}/schema")), &answer),
            Err(e) => vec![format!("not JSON: {e}")],
        }
    }

    /// What in an event of the stream does not follow the document's item schema and the data schema of its name.
    pub fn event_violations(&self, name: &str, id: &str, data: &str) -> Vec<String> {
        let item = json!({"event": name, "id": id, "data": data});
        let violations = messages(&self.schema(EVENTS), &item);
        if !violations.is_empty() {
            return violations;
        }
        let kinds = self.document.pointer(&format!("{EVENTS}/oneOf")).and_then(Value::as_array).unwrap();
        for index in 0..kinds.len() {
            let kind = self.resolve(&format!("{EVENTS}/oneOf/{index}"));
            if self.document.pointer(&format!("{kind}/properties/event/const")).and_then(Value::as_str) == Some(name) {
                return match serde_json::from_str(data) {
                    Ok(data) => messages(&self.schema(&format!("{kind}/properties/data/contentSchema")), &data),
                    Err(e) => vec![format!("not JSON: {e}")],
                };
            }
        }
        vec![format!("no event named {name}")]
    }

    // Where the schema at `at` leads once its references are followed.
    fn resolve(&self, at: &str) -> String {
        match self.document.pointer(at).and_then(|node| node.get("$ref")).and_then(Value::as_str) {
            Some(target) => self.resolve(target.strip_prefix('#').unwrap()),
            None => at.to_owned(),
        }
    }

    fn schema(&self, at: &str) -> Validator {
        jsonschema::options()
            .with_draft(Draft::Draft202012)
            .with_registry(&self.registry)
            .build(&json!({"$ref": format!("{BASE}#{}", utf8_percent_encode(at, FRAGMENT))}))
            .unwrap_or_else(|e| panic!("the schema at {at}: {e}"))
    }
}

fn messages(schema: &Validator, value: &Value) -> Vec<String> {
    schema.iter_errors(value).map(|error| format!("{}: {error}", error.instance_path())).collect()
}

// A copy of the document whose object schemas take no field they do not name. Parts that a schema combines with its
// own properties or with other parts (allOf) stay open and the whole is closed instead: in JSON Schema 2020-12 a
// closed part would refuse the fields the others name.
fn strict(document: &Value) -> Value {
    let mut copy = document.clone();
    let mut parts = HashSet::new();
    find_parts(&copy, &mut parts);
    close(&mut copy, "", false, &parts);
    copy
}

fn combines(schema: &Map<String, Value>, keyword: &str) -> bool {
    keyword == "allOf" || (schema.contains_key("properties") && (keyword == "oneOf" || keyword == "anyOf"))
}

fn find_parts(node: &Value, parts: &mut HashSet<String>) {
    if let Value::Object(object) = node {
        for keyword in ["allOf", "oneOf", "anyOf"] {
            if let Some(Value::Array(combined)) = object.get(keyword).filter(|_| combines(object, keyword)) {
                let references = combined.iter().filter_map(|part| part.get("$ref")).filter_map(Value::as_str);
                parts.extend(references.map(|target| target.trim_start_matches('#').to_owned()));
            }
        }
    }
    match node {
        Value::Object(object) => object.values().for_each(|child| find_parts(child, parts)),
        Value::Array(items) => items.iter().for_each(|child| find_parts(child, parts)),
        _ => {}
    }
}

fn close(node: &mut Value, at: &str, part: bool, parts: &HashSet<String>) {
    match node {
        Value::Object(object) => {
            if (object.contains_key("properties") || object.contains_key("allOf")) && !part && !parts.contains(at) {
                object.insert("unevaluatedProperties".to_owned(), Value::Bool(false));
            }
            let names: Vec<String> = object.keys().cloned().collect();
            for name in names {
                let combined = combines(object, &name);
                let value = object.get_mut(&name).unwrap();
                if name == "properties" {
                    // The names of the properties are no schemas; their values are.
                    if let Value::Object(properties) = value {
                        for (property, schema) in properties.iter_mut() {
                            close(schema, &format!("{at}/properties/{}", escape(property)), false, parts);
                        }
                    }
                } else if let (Value::Array(items), true) = (&mut *value, combined) {
                    for (index, item) in items.iter_mut().enumerate() {
                        close(item, &format!("{at}/{name}/{index}"), true, parts);
                    }
                } else {
                    close(value, &format!("{at}/{}", escape(&name)), false, parts);
                }
            }
        }
        Value::Array(items) => {
            for (index, item) in items.iter_mut().enumerate() {
                close(item, &format!("{at}/{index}"), false, parts);
            }
        }
        _ => {}
    }
}

// A token of a JSON pointer.
fn escape(token: &str) -> String {
    token.replace('~', "~0").replace('/', "~1")
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
