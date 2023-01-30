package io.github.oybek.xo.integration

import cats.Parallel
import cats.effect.concurrent.Ref
import cats.effect.{Sync, Timer}
import cats.implicits.{catsSyntaxApplicative, catsSyntaxApplicativeError, catsSyntaxApplicativeId, catsSyntaxOptionId, toFunctorOps}
import cats.syntax.flatMap._
import io.github.oybek.xo.model.Outcome.{Draw, Win}
import io.github.oybek.xo.model.{Board, Coord}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import telegramium.bots._
import telegramium.bots.high.implicits.methodOps
import telegramium.bots.high.{Api, LongPollBot, Methods}

import scala.concurrent.duration.DurationInt
import scala.util.Random

class TelegramGate[F[_] : Sync : Timer : Parallel](matches: Ref[F, Map[(Long, Int), Board]], api: Api[F]) extends LongPollBot[F](api) {
  override def onMessage(message: Message): F[Unit] =
    message.text match {
      case _ => onTextMessage(message)
    }

  override def onCallbackQuery(query: CallbackQuery): F[Unit] =
    query match {
      case CallbackQuery(_, _, Some(message), _, _, Some(data), _) if data == "x" || data == "o" =>
        startNewGame(message, data)

      case CallbackQuery(_, _, Some(message), _, _, Some(data), _) if data.matches("[0-9]+,[0-9]+") =>
        handlePlayerTurn(message, data)

      case CallbackQuery(_, _, Some(message), _, _, Some(data), _) if data == "/start" =>
        onTextMessage(message)

      case _ => ().pure[F]
    }

  private def onTextMessage(message: Message): F[Unit] =
    Methods.sendMessage(
      chatId = ChatIntId(message.chat.id),
      text =
        """
          |Нажми x или o чтобы начать игру
          |
          |Но предупрежу заранее...
          |Ты меня никогда не победишь!
          |""".stripMargin,
      replyMarkup =
        InlineKeyboardMarkup(List(List(
          InlineKeyboardButton("x", callbackData = "x".some),
          InlineKeyboardButton("o", callbackData = "o".some)
        ))).some
    ).exec(api).void

  private def handlePlayerTurn(message: Message, data: String): F[Unit] = {
    val x :: y :: Nil = data.split(",").map(_.toInt).toList
    matches.get.map(_.get((message.chat.id, message.messageId))).flatMap {
      case Some(board) if board.outcome.nonEmpty =>
        onGameEnd(message, board)

      case Some(board@Board(xs, os)) if xs.contains(Coord(x, y)) || os.contains(Coord(x, y)) =>
        onBusyCellPress(message, board)

      case Some(board) =>
        val board0 = board.put(Coord(x, y))
        val board1 = Master.play(board0).map(board0.put).getOrElse(board0)
        board1.outcome.fold(onGameContinue(message, board1)) { _ =>
          onGameEnd(message, board1)
        }

      case None => ().pure[F]
    }
  }

  private def onGameContinue(message: Message, board1: Board): F[Unit] =
    for {
      _ <- matches.update(_.updated((message.chat.id, message.messageId), board1))
      _ <- Methods.editMessageText(
        chatId = ChatIntId(message.chat.id).some,
        messageId = message.messageId.some,
        text = Random.shuffle(List("Твой ход", "Ходи", "Ты ходишь", "Ходи уже", "Я уже сходил")).head,
        replyMarkup = drawBoard(board1).some
      ).exec(api).attempt.void
    } yield ()

  private def onBusyCellPress(message: Message, board: Board): F[Unit] =
    Methods.editMessageText(
      chatId = ChatIntId(message.chat.id).some,
      messageId = message.messageId.some,
      text = "Эта клетка занята!",
      replyMarkup = drawBoard(board).some
    ).exec(api).attempt.void

  private def onGameEnd(message: Message, board: Board): F[Unit] =
    for {
      _ <- Methods.editMessageText(
        chatId = ChatIntId(message.chat.id).some,
        messageId = message.messageId.some,
        text = board.outcome match {
          case Some(Win(_)) => "Ахахах!\nТы еще и проиграл!"
          case _ => "Вот и все!\nТы не выиграл!"
        },
        replyMarkup = drawBoard(board).some.map {
          case InlineKeyboardMarkup(inlineKeyboard) =>
            InlineKeyboardMarkup(inlineKeyboard ++ List(List(InlineKeyboardButton("Еще раз", callbackData = Some("/start")))))
        }
      ).exec(api).attempt.void
      _ <- matches.update(_.removed((message.chat.id, message.messageId)))
      _ <- board.outcome match {
        case Some(Win(_)) =>
          Timer[F].sleep(2.second) >>
            Methods
              .sendSticker(ChatIntId(message.chat.id), sticker = Stickers.laugh)
              .exec(api)
              .void
        case _ => ().pure[F]
      }
    } yield ()

  private def startNewGame(message: Message, data: String): F[Unit] =
    for {
      board <- createBoard(data).pure[F]
      _ <- Methods.sendSticker(ChatIntId(message.chat.id), sticker = Stickers.smocking).exec(api)
      gameMessage <- Methods.sendMessage(
        chatId = ChatIntId(message.chat.id),
        text = "Твой ход!",
        replyMarkup = drawBoard(board).some
      ).exec(api)
      _ <- matches.update(_.updated((gameMessage.chat.id, gameMessage.messageId), board))
    } yield ()

  private def drawBoard(board: Board): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
      List.tabulate(3, 3) {
        case (x, y) if board.xs.contains(Coord(x, y)) =>
          val c = if (board.winCoords(board.xs).exists(_.contains(Coord(x, y)))) "[x]" else "x"
          InlineKeyboardButton(c, callbackData = s"$x,$y".some)
        case (x, y) if board.os.contains(Coord(x, y)) =>
          val c = if (board.winCoords(board.os).exists(_.contains(Coord(x, y)))) "[o]" else "o"
          InlineKeyboardButton(c, callbackData = s"$x,$y".some)
        case (x, y) => InlineKeyboardButton(text = " ", callbackData = s"$x,$y".some)
      }
    )

  private def createBoard(s: String) = {
    if (s == "x")
      Board.empty
    else
      Master.play(Board.empty)
        .map(Board.empty.put)
        .getOrElse(Board.empty)
  }

  private val log = Slf4jLogger.getLoggerFromName[F]("telegram")
}
