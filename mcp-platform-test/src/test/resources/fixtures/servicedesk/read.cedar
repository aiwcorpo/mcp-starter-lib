// Reading follows the user's own rights in the system; the server adds the group check.

@id("servicedesk-read")
permit (
  principal in UserGroup::"mcp-servicedesk-read",
  action == ServiceDesk::Action::"read",
  resource
)
when { resource is ServiceDesk::Queue || resource is ServiceDesk::Ticket };
