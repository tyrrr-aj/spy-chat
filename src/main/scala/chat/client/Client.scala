package chat.client

import util.control.Breaks._
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, ActorSystem, FSM, PoisonPill, Props}
import chat.{LeftRoom, _}
import chat.client.Client.{Data, State}
import com.typesafe.config.ConfigFactory


object Client {
  def props(serverActorRef: ActorSelection): Props =
    Props(new Client(serverActorRef))

  // client internals
  sealed trait ClientMessage
  final case class InputLineMsg(line: String) extends ClientMessage

  sealed trait Command
  final case class JoinCmd(room: String) extends Command
  final case object GetRoomsCmd extends Command
  final case object LeaveCmd extends Command
  final case object LogoutCmd extends Command
  final case object UnknownCmd extends Command

  final case class ParsingError(private val message: String = "",
                                private val cause: Throwable = None.orNull)
    extends Exception(message, cause)


  // client states
  sealed trait State
  case object Connecting extends State
  case object Connected extends State
  case object Chatting extends State

  // client data
  sealed trait Data
  case object Uninitialized extends Data
  case class SessionData(nick: String, remoteRef: ActorRef) extends Data
  case class ConvData(nick: String, room: String, remoteRef: ActorRef) extends Data
}

class Client(serverActorRef: ActorSelection) extends Actor
  with ActorLogging
  with FSM[State, Data] {
  import Client._

  override def preStart(): Unit = log.info("Client starting.")
  override def postStop(): Unit = log.info("Client stopped.")

  startWith(Connecting, Uninitialized)

  when (Connecting) {
    case Event(InputLineMsg(line), Uninitialized) =>
      val nick = line.split(" ").head
      if (nick.forall(_.isLetter))
        serverActorRef ! Login(nick)
      else
        println("Nickname can contain letters only!")
      stay using Uninitialized

    case Event(LoggedIn(nick, remoteActor), Uninitialized) =>
      println("Logged in successfully.")
      goto(Connected) using SessionData(nick, remoteActor)

    case Event(NameTaken(nick: String), Uninitialized) =>
      println(s"$nick is taken, choose another one!")
      stay using Uninitialized
  }

  when(Connected) {
    case Event(Joined(room), SessionData(nick, remoteRef)) =>
      println("Joined room!")
      goto(Chatting) using ConvData(nick, room, remoteRef)

    case Event(RespondChatRooms(rooms: List[String]), data: SessionData) =>
      println("CHAT ROOMS:")
      rooms.foreach(r => println(s"-> $r"))
      stay using data

    case Event(InputLineMsg(line), SessionData(nick, remoteRef)) =>
      try {
        parseLine(line) match {
          case Left(cmd) =>
            cmd match {
              case JoinCmd(room) =>
                remoteRef ! Join(nick, room)
                stay
              case GetRoomsCmd =>
                remoteRef ! RequestChatRooms
                stay
              case LogoutCmd =>
                remoteRef ! RequestLogout
                goto(Connecting) using Uninitialized
              case UnknownCmd =>
                println(s"Unknown command: $cmd")
                stay
              case _ =>
                println(s"Command $cmd is not supported in this state.")
                stay
            }
          case Right(_) =>
            println("Only commands are supported in this state.")
            stay
        }
      } catch {
        case ParsingError(message, _) =>
          println(s"Parsing error occurred: $message")
          stay
      }

  }

  when(Chatting) {
    case Event(ChatMessage(from, msg), _: ConvData) =>
      println(s">>> $from: $msg")
      stay

    case Event(ChatLog(log), _: ConvData) =>
      log.map({case ChatMessage(from, msg) => s">>> $from: $msg"}).foreach(println)
      stay

    case Event(LeftRoom, ConvData(nick, room, remoteRef)) =>
      println(s"Left $room")
      goto(Connected) using SessionData(nick, remoteRef)

    case Event(InputLineMsg(line), ConvData(nick, _, remoteRef)) =>
      try {
        parseLine(line) match {
          case Left(cmd) =>
            cmd match {
              case LeaveCmd => remoteRef ! Leave
              case UnknownCmd => println(s"Unknown command: $cmd")
              case _ => println(s"Command $cmd is not supported in this state.")
            }
          case Right(msg) => remoteRef ! ChatMessage(nick, msg)
        }
      } catch {
        case ParsingError(message, _) => println(s"Parsing error occurred: $message")
      }

      stay
  }

  whenUnhandled {
    case Event(message, _) =>
      log.error(s"Unexpected message: $message")
      stay
  }

  def parseLine(line: String): Either[Command, String] = {
    val trimmed = line.trim()

    trimmed.split(" ", 2) match {
      case Array() =>
        Right("")
      case Array(head) =>
        if (head.take(1) == "\\")
          Left(parseCommand(head.drop(1), None))
        else
          Right(head)
      case Array(head, rem) =>
        if (head.take(1) == "\\")
          Left(parseCommand(head.drop(1), Some(rem)))
        else
          Right(trimmed)
    }
  }

  def parseCommand(command: String, rem: Option[String]): Command = {
    command match {
      case "rooms" =>
        if (rem.isDefined)
          throw ParsingError(message = "Command: \\rooms takes no parameters.")
        else
          GetRoomsCmd
      case "join" =>
        if (rem.isEmpty || rem.get.split(" ").length > 1)
          throw ParsingError(message = "Command: \\join takes exactly one parameter.")
        else
          JoinCmd(rem.get)
      case "leave" =>
        if (rem.isDefined)
          throw ParsingError(message = "Command: \\leave takes no parameters.")
        else
          LeaveCmd
      case "logout" =>
        if (rem.isDefined)
          throw ParsingError(message = "Command: \\logout takes no parameters.")
        else
          LogoutCmd
      case _ =>
        UnknownCmd
    }
  }

  initialize()
}


object Main {
  def main(args: Array[String]): Unit = {

    val usage =
      """
        | usage: run [-ch client_host] [-cp client_port] [-sh server_host] [-sp server_port]
        | By default client is run on 127.0.0.1:2553, targeting local server
        | on 127.0.0.1:2551
      """.stripMargin

    var clientHost = "127.0.0.1"
    var clientPort = 2553
    var serverHost = clientHost
    var serverPort = 2551

    if (args.length > 0) {
      val argsList = args.toList

      def parseOptions(args: List[String]): Unit = {
        args match {
          case Nil =>
          case "-ch" :: host :: tail =>
            clientHost = host
            parseOptions(tail)
          case "-cp" :: port :: tail =>
            clientPort = port.toInt
            parseOptions(tail)
          case "-sh" :: host :: tail =>
            serverHost = host
            parseOptions(tail)
          case "-sp" :: port :: tail =>
            serverPort = port.toInt
            parseOptions(tail)
          case opt =>
            println(s"Unknown option: $opt")
            println(usage)
            System.exit(1)
        }
      }

      try {
        parseOptions(argsList)
      } catch {
        case _: NumberFormatException =>
          println("Port numbers must be integers.")
          println(usage)
          System.exit(1)
        case e: Throwable =>
          println(s"Error occurred: $e")
          println(usage)
          System.exit(1)
      }
    }

    val config = ConfigFactory.parseString(
      s"""
         |akka.remote.netty.tcp.hostname="$clientHost"
         |akka.remote.netty.tcp.port=$clientPort
       """.stripMargin)
      .withFallback(ConfigFactory.load("client"))

    val system = ActorSystem("chat-client-system", config)

    val serverActorRef =
      system.actorSelection(s"akka.tcp://chat-server-system@$serverHost:$serverPort/user/server")

    val clientActorRef = system.actorOf(Client.props(serverActorRef), "client")

    import Client._
    breakable {
      for (line <- scala.io.Source.stdin.getLines()) {
        line match {
          case "STOP" =>
            clientActorRef ! PoisonPill
          case _ =>
            clientActorRef ! InputLineMsg(line)
        }
      }
    }

    println("Done!")
    system.terminate()
  }
}
