[START-REPLACE]
[OLD]
#[cfg(desktop)]
use crate::ai_service::skill_agent::command_executor;
[/OLD]
[NEW]
use crate::ai_service::skill_agent::command_executor;
[/NEW]
[END-REPLACE]

[START-REPLACE]
[OLD]
        #[cfg(desktop)]
        ToolDefinition::new(
            "execute_command",
[/OLD]
[NEW]
        ToolDefinition::new(
            "execute_command",
[/NEW]
[END-REPLACE]

[START-REPLACE]
[OLD]
        #[cfg(desktop)]
        "execute_command" => {
[/OLD]
[NEW]
        "execute_command" => {
[/NEW]
[END-REPLACE]
