using Grpc.Net.Client;
using ChatApp;
using Grpc.Core;

// Prompt for gRPC server address
Console.Write("Enter gRPC server address (e.g., http://localhost:50051): ");
string? serverAddress = Console.ReadLine();
if (string.IsNullOrWhiteSpace(serverAddress))
{
    serverAddress = "http://localhost:50051";
    Console.WriteLine($"Using default address: {serverAddress}");
}

// Prompt for sender name
Console.Write("Enter your name: ");
string? senderName = Console.ReadLine();
if (string.IsNullOrWhiteSpace(senderName))
{
    senderName = "Anonymous";
}

// Create gRPC channel and client
try
{
    var channel = GrpcChannel.ForAddress(serverAddress);
    var client = new ChatService.ChatServiceClient(channel);

    var call = client.Chat();

    // Background task to receive messages
    var readTask = Task.Run(async () =>
    {
        try
        {
            while (await call.ResponseStream.MoveNext())
            {
                var response = call.ResponseStream.Current;
                Console.WriteLine($"[{response.Timestamp}] {response.Sender}: {response.Content}");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[System] Connection error: {ex.Message}");
        }
    });

    // Send loop
    Console.WriteLine("\nChat started! Type messages (press Enter on empty line to exit):");
    while (true)
    {
        string? input = Console.ReadLine();
        if (string.IsNullOrEmpty(input)) break;

        await call.RequestStream.WriteAsync(new MessageRequest
        {
            Sender = senderName,
            Content = input
        });
    }

    await call.RequestStream.CompleteAsync();
    await readTask;
}
catch (Exception ex)
{
    Console.WriteLine($"[Error] Failed to connect to server: {ex.Message}");
}

Console.WriteLine("Chat ended.");