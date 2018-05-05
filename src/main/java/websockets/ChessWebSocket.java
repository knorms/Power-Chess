package websockets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import board.Board.EmptySpace;
import board.BoardObject;
import board.IllegalMoveException;
import board.Location;
import game.Color;
import game.Game;
import game.Game.GameState;
import game.Move;
import pieces.Bishop;
import pieces.King;
import pieces.Knight;
import pieces.Piece;
import pieces.Queen;
import pieces.Rook;
import players.GuiPlayer;
import players.Player;
import poweractions.Armageddon;
import poweractions.PowerAction;
import poweractions.Rewind;
import poweractions.SendAway;
import powerups.BlackHole;
import powerups.Invulnerability;
import powerups.PowerObject;
import powerups.PowerUp;

/**
 * WebSocket allows server to communicate with client.
 *
 * @author dwoods, knormand
 *
 */
@WebSocket
public class ChessWebSocket {
  private static final Gson GSON = new Gson();
  private static final Queue<Session> SESSIONS = new ConcurrentLinkedQueue<>();

  private static int nextGameId = 0;
  private static int nextPlayerId = 0;

  private static final Map<Integer, Game> GAME_ID_MAP = new HashMap<>();
  private static final Map<Integer, List<Integer>> GAME_PLAYER_MAP =
      new HashMap<>();
  private static final Map<Integer, Session> PLAYER_SESSION_MAP =
      new HashMap<>();
  private static final Map<Integer, String> PLAYER_NAME_MAP = new HashMap<>();
  private static final Map<Integer, Boolean> PLAYER_DRAW_MAP = new HashMap<>();

  /**
   * Enumerates allowable websocket message types.
   *
   * @author dwoods
   *
   */
  private enum MessageType {
    CREATE_GAME, JOIN_GAME, GAME_OVER, REQUEST_DRAW, PLAYER_ACTION, GAME_UPDATE,
    ILLEGAL_ACTION, ERROR
  }

  /**
   * Enumerates player actions.
   *
   * @author dwoods
   *
   */
  private enum Action {
    NONE, MOVE, SELECT_POWER, SELECT_SQUARE, SELECT_PIECE, MOVE_THIS
  }

  /**
   * Enumerates reasons a game can end.
   *
   * @author dwoods
   *
   */
  private enum GameEndReason {
    MATE, RESIGNATION, TIME, DRAW_AGREED
  }

  /**
   * Enumerates game end results.
   *
   * @author dwoods
   *
   */
  private enum GameResult {
    WIN, LOSS, DRAW
  }

  /**
   * Enumerates types of entities that can be interacted with (on board and
   * off).
   *
   * @author dwoods
   *
   */
  private enum EntityTypes {
    NOTHING, PIECE, POWER, OTHER
  }

  /**
   * Enumerates the types of chess pieces.
   *
   * @author dwoods
   *
   */
  private enum PieceIds {
    KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN
  }

  /**
   * Enumerates the rarities of PowerObjects.
   *
   * @author dwoods
   *
   */
  private enum PowerRarities {
    COMMON, RARE, LEGENDARY
  }

  /**
   * Enumerates all common PowerActions.
   *
   * @author dwoods
   *
   */
  private enum CommonPowers {
    ADJUST, REWIND, SECOND_EFFORT, SHIELD, SWAP
  }

  /**
   * Enumerates all rare PowerActions.
   *
   * @author dwoods
   *
   */
  private enum RarePowers {
    BLACK_HOLE, ENERGIZE, EYE_FOR_AN_EYE, SAFETY_NET, SEND_AWAY
  }

  /**
   * Enumerates all legendary PowerActions.
   *
   * @author dwoods
   *
   */
  private enum LegendaryPowers {
    ARMAGEDDON, AWAKEN, CLONE, REANIMATE
  }

  /**
   * Enumerates all other entities that could appear on the board.
   *
   * @author knorms
   *
   */
  private enum OtherEntities {
    BLACK_HOLE
  }

  /**
   * On connect, add session to the queue.
   *
   * @param session
   *          Session that just connected.
   * @throws IOException
   *           If session cannot be added to the queue.
   */
  @OnWebSocketConnect
  public void connected(Session session) throws IOException {
    SESSIONS.add(session);
  }

  /**
   * Remove session from the queue.
   *
   * @param session
   *          Session to be removed.
   * @param statusCode
   *          Code indicating why session was closed.
   * @param reason
   *          Reason session was closed.
   */
  @OnWebSocketClose
  public void closed(Session session, int statusCode, String reason) {
    SESSIONS.remove(session);
  }

  /**
   * Respond to recieved messages by parsing and responding with appropriate
   * info/updates.
   *
   * @param session
   *          Session that sent message.
   * @param message
   *          Message recieved.
   * @throws IOException
   *           In case the response JsonObject doesn't get sent properly.
   */
  @OnWebSocketMessage
  public void message(Session session, String message) throws IOException {
    JsonObject received = GSON.fromJson(message, JsonObject.class);

    int typeIndex = received.get("type").getAsInt();
    MessageType messageType = MessageType.values()[typeIndex];

    switch (messageType) {
      case CREATE_GAME:
        createGame(session, received);
        break;

      case JOIN_GAME:
        addPlayer(session, received);
        break;

      case REQUEST_DRAW:
        requestDraw(session, received);
        break;

      case PLAYER_ACTION:
        int actionIndex = received.get("action").getAsInt();
        Action action = Action.values()[actionIndex];

        switch (action) {
          case MOVE:
            makeMove(session, received);
            break;
          case SELECT_POWER:
            powerSelect(session, received);
            break;
          case SELECT_SQUARE:
            // TODO implement select square;
            break;
          case SELECT_PIECE:
            // TODO implement select piece
            break;
          case MOVE_THIS:
            // TODO implement move this
          case NONE:
          default:
            break;
        }
        break;

      case GAME_OVER:
        gameOver(session, received);
        break;

      case ERROR:
        // TODO deal with error
        break;

      default:
        break;

    }

  }

  /**
   * Called when server receives a GAME_OVER message. Server will inform both
   * players that the game is over and why (e.g. time, forfeit).
   *
   * @param session
   *          The session of the player requesting a draw.
   * @param received
   *          The JsonObject sent by session.
   * @throws IOException
   *           In case the response message couldn't be sent back properly.
   */
  private void gameOver(Session session, JsonObject received)
      throws IOException {
    int playerId = received.get("playerId").getAsInt();
    int gameId = received.get("gameId").getAsInt();
    List<Integer> playerList = GAME_PLAYER_MAP.get(gameId);

    int reason = received.get("reason").getAsInt();

    JsonObject response = new JsonObject();
    response.addProperty("type", MessageType.GAME_OVER.ordinal());
    response.addProperty("reason", reason);
    response.addProperty("result", GameResult.LOSS.ordinal());

    session.getRemote().sendString(GSON.toJson(response));

    int otherId = -1;
    // Retrieves the id of the other player in the game.
    for (int id : playerList) {
      if (id != playerId) {
        otherId = id;
        break;
      }
    }

    // If other player id exists, then update them too
    if (otherId != -1) {
      Session otherSession = PLAYER_SESSION_MAP.get(otherId);
      response.remove("result");
      response.addProperty("result", GameResult.WIN.ordinal());
      otherSession.getRemote().sendString(GSON.toJson(response));
    }

  }

  /**
   * Called when server receives a REQUEST_DRAW message. If the other player is
   * awaiting draw response, game will end in draw. Otherwise, server will ask
   * the other player if they'd like to draw as well.
   *
   * @param session
   *          The session of the player requesting a draw.
   * @param received
   *          The JsonObject sent by session.
   * @throws IOException
   *           In case the response message couldn't be sent back properly.
   */
  private void requestDraw(Session session, JsonObject received)
      throws IOException {
    int playerId = received.get("playerId").getAsInt();
    int gameId = received.get("gameId").getAsInt();
    List<Integer> playerList = GAME_PLAYER_MAP.get(gameId);
    int otherId = -1;
    // Retrieves the id of the other player in the game.
    for (int id : playerList) {
      if (id != playerId) {
        otherId = id;
        break;
      }
    }
    // If other player id does not exist. Return an illegal action message.
    if (otherId == -1) {
      JsonObject response = new JsonObject();
      response.addProperty("type", MessageType.ILLEGAL_ACTION.ordinal());
      session.getRemote().sendString(GSON.toJson(response));
      return;
    }

    boolean otherDraw = PLAYER_DRAW_MAP.get(otherId);
    Session otherSession = PLAYER_SESSION_MAP.get(otherId);
    // If the other player is awaiting a draw message, end game. Otherwise a
    if (otherDraw) {
      JsonObject response = new JsonObject();
      response.addProperty("type", MessageType.GAME_OVER.ordinal());
      response.addProperty("reason", GameEndReason.DRAW_AGREED.ordinal());
      response.addProperty("result", GameResult.DRAW.ordinal());

      session.getRemote().sendString(GSON.toJson(response));
      otherSession.getRemote().sendString(GSON.toJson(response));
    } else {
      JsonObject response = new JsonObject();
      response.addProperty("type", MessageType.REQUEST_DRAW.ordinal());
      otherSession.getRemote().sendString(GSON.toJson(response));
    }
  }

  /**
   * Called when server receives a POWER_SELECTION action message. Server
   * attempts to execute power action; returns ILLEGAL_ACTION if follow-up was
   * illegal, or GAME_UPDATE to both players if successfully executed.
   *
   * @param session
   *          The session of the player requesting a draw.
   * @param received
   *          The JsonObject sent by session.
   * @throws IOException
   *           In case the response message couldn't be sent back properly.
   */
  private void powerSelect(Session session, JsonObject received)
      throws IOException {
    boolean selection = received.get("selection").getAsBoolean();
    int gameId = received.get("gameId").getAsInt();
    int playerId = received.get("playerId").getAsInt();
    Game game = GAME_ID_MAP.get(gameId);

    List<PowerAction> actionOptions = game.getActionOptions();
    PowerAction selected;
    int index = selection ? 1 : 0;
    selected = actionOptions.get(index);
    game.getActivePlayer().setAction(selected);
    Location whereCaptured =
        game.getActivePlayer().getAction().getWhereCaptured();

    Collection<BoardObject> preWhereCaptured = game.getObjsAt(whereCaptured);
    Collection<BoardObject> preLoc = null, preStart = null, preEnd = null;

    game.setGameState(GameState.WAITING_FOR_POWERUP_EXEC);
    JsonObject followUp = received.get("followUp").getAsJsonObject();
    Object input = null;
    if (followUp.has("row")) {
      int row = followUp.get("row").getAsInt();
      int col = followUp.get("col").getAsInt();
      input = new Location(row, col);
      preLoc = game.getObjsAt((Location) input);
    } else if (followUp.has("from")) {
      input = getMove(followUp);
      preStart = game.getObjsAt(((Move) input).getStart());
      preEnd = game.getObjsAt(((Move) input).getEnd());
    }

    // if input not valid, return ILLEGAL_ACTION
    if (!selected.validInput(input)) {
      JsonObject response = new JsonObject();
      response.addProperty("type", MessageType.ILLEGAL_ACTION.ordinal());
      session.getRemote().sendString(GSON.toJson(response));
    }

    // otherwise execute action and compile GAME_UPDATE
    game.executePowerAction(input);
    game.setGameState(GameState.WAITING_FOR_MOVE);

    JsonArray updates = new JsonArray();
    // Check where captured
    updates.addAll(getDifference(whereCaptured, preWhereCaptured, game));

    // Check specified followUp location
    if (preLoc != null) {
      Location loc = (Location) input;
      updates.addAll(getDifference(loc, preLoc, game));
    }

    // Check followUp move start and end
    if (preStart != null) {
      Location startLoc = ((Move) input).getStart();
      updates.addAll(getDifference(startLoc, preStart, game));
    }
    if (preEnd != null) {
      Location endLoc = ((Move) input).getEnd();
      updates.addAll(getDifference(endLoc, preEnd, game));
    }

    // If Armageddon, find all former pawn locations and curret king locations
    if (selected instanceof Armageddon) {
      // update invulnerability at king locs
      for (Location loc : ((Armageddon) selected).getKingLocations()) {
        Piece p = game.getPieceAt(loc);
        JsonObject updatePart = new JsonObject();
        updatePart.addProperty("row", loc.getRow());
        updatePart.addProperty("col", loc.getCol());
        updatePart.addProperty("state", EntityTypes.PIECE.ordinal());
        updatePart.addProperty("color", p.getColor() == Color.WHITE);
        updatePart.addProperty("piece", PieceIds.KING.ordinal() + 6);
        updates.add(updatePart);
      }

      // update pawn locs have nothing
      for (Location loc : ((Armageddon) selected).getPawnLocations()) {
        JsonObject updatePart = new JsonObject();
        updatePart.addProperty("row", loc.getRow());
        updatePart.addProperty("col", loc.getCol());
        updatePart.addProperty("state", EntityTypes.NOTHING.ordinal());
        updates.add(updatePart);
      }

      // If Rewind, check game history
    } else if (selected instanceof Rewind) {
      List<Move> history = game.getHistory();
      Move move = history.get(history.size() - 2);
      Location start = move.getStart();
      Location end = move.getEnd();
      // if rewind was actually executed, end will be empty
      if (game.isEmpty(end)) {
        // update that former end loc is empty
        JsonObject updatePart = new JsonObject();
        updatePart.addProperty("row", end.getRow());
        updatePart.addProperty("col", end.getCol());
        updatePart.addProperty("state", EntityTypes.NOTHING.ordinal());
        updates.add(updatePart);

        // update that piece is back at former start loc
        Piece p = game.getPieceAt(start);
        JsonObject updatePart2 = new JsonObject();
        updatePart2.addProperty("row", start.getRow());
        updatePart2.addProperty("col", start.getCol());
        updatePart2.addProperty("state", EntityTypes.PIECE.ordinal());
        updatePart2.addProperty("color", p.getColor() == Color.WHITE);
        updatePart2.addProperty("piece",
            PieceIds.valueOf(p.getClass().getSimpleName()).ordinal());
        updates.add(updatePart2);
      }

      // If SendAway, check where sent
    } else if (selected instanceof SendAway) {
      Location loc = ((SendAway) selected).getEndLocation();
      Piece p = game.getPieceAt(loc);
      // update that endloc now has a piece
      JsonObject updatePart = new JsonObject();
      updatePart.addProperty("row", loc.getRow());
      updatePart.addProperty("col", loc.getCol());
      updatePart.addProperty("state", EntityTypes.PIECE.ordinal());
      updatePart.addProperty("color", p.getColor() == Color.WHITE);
      updatePart.addProperty("piece",
          PieceIds.valueOf(p.getClass().getSimpleName()).ordinal());
      updates.add(updatePart);
    }

    JsonObject response = new JsonObject();
    response.addProperty("type", MessageType.GAME_UPDATE.ordinal());
    response.add("updates", updates);

    List<Integer> playerList = GAME_PLAYER_MAP.get(gameId);
    int otherId = -1;
    // Retrieves the id of the other player in the game.
    for (int id : playerList) {
      if (id != playerId) {
        otherId = id;
        break;
      }
    }

    // update active player
    session.getRemote().sendString(GSON.toJson(response));

    // If other player id exists, then update them too
    if (otherId != -1) {
      Session otherSession = PLAYER_SESSION_MAP.get(otherId);
      otherSession.getRemote().sendString(GSON.toJson(response));
    }
  }

  /**
   * Get the pre-post difference between objects on a board location and return
   * a JsonArray representing all the changes.
   *
   * @param loc
   *          Location to check differences at.
   * @param preObjs
   *          Objects on space before power action execution.
   * @param game
   *          Game that was modified by power action.
   * @return JsonArray representing all changes at specified location.
   */
  private JsonArray getDifference(Location loc, Collection<BoardObject> preObjs,
      Game game) {
    JsonArray updates = new JsonArray();

    Collection<BoardObject> postObjs = game.getObjsAt(loc);
    postObjs.removeIf(obj -> obj instanceof EmptySpace);
    preObjs.removeIf(obj -> obj instanceof EmptySpace);

    // if there was something and now there's nothing
    if (postObjs.isEmpty() && !preObjs.isEmpty()) {
      JsonObject updatePart = new JsonObject();
      updatePart.addProperty("state", EntityTypes.NOTHING.ordinal());
      updates.add(updatePart);
    }

    postObjs.removeAll(preObjs);
    // if there are new objects on location, add difference updates
    for (BoardObject obj : postObjs) {
      JsonObject updatePart = new JsonObject();
      updatePart.addProperty("row", loc.getRow());
      updatePart.addProperty("col", loc.getCol());

      // if added piece to loc
      if (obj instanceof Piece) {
        Piece p = ((Piece) obj);
        updatePart.addProperty("state", EntityTypes.PIECE.ordinal());
        updatePart.addProperty("color", p.getColor() == Color.WHITE);
        updatePart.addProperty("piece",
            PieceIds.valueOf(p.getClass().getSimpleName()).ordinal());

        // if added blackhole to loc
      } else if (obj instanceof BlackHole) {
        updatePart.addProperty("state", EntityTypes.OTHER.ordinal());
        updatePart.addProperty("other", OtherEntities.BLACK_HOLE.ordinal());

        // if added invulnerability to loc
      } else if (obj instanceof Invulnerability) {
        Piece p = game.getPieceAt(loc);
        updatePart.addProperty("state", EntityTypes.PIECE.ordinal());
        updatePart.addProperty("color", p.getColor() == Color.WHITE);
        updatePart.addProperty("piece",
            PieceIds.valueOf(p.getClass().getSimpleName()).ordinal() + 6);
      }

      updates.add(updatePart);
    }

    return updates;
  }

  /**
   * Called when server receives a MOVE action message. Server attempts to make
   * desired move and either replies replies with ILLEGAL_MOVE or updates each
   * player as to the changed board state.
   *
   * @param session
   *          The session of the player requesting a draw.
   * @param received
   *          The JsonObject sent by session.
   * @throws IOException
   *           In case the response message couldn't be sent back properly.
   */
  private void makeMove(Session session, JsonObject received)
      throws IOException {
    JsonObject moveJson = received.get("move").getAsJsonObject();
    Move move = getMove(moveJson);

    int gameId = received.get("gameId").getAsInt();
    int playerId = received.get("playerId").getAsInt();
    Game game = GAME_ID_MAP.get(gameId);
    Player player = game.getActivePlayer();
    player.setMove(move);

    try {
      game.turn();
    } catch (IllegalMoveException e) {
      // If illegal move, send back an illegal action message to the session
      // owner.
      JsonObject response = new JsonObject();
      response.addProperty("type", MessageType.ILLEGAL_ACTION.ordinal());
      session.getRemote().sendString(GSON.toJson(response));
      return;
    }

    PLAYER_DRAW_MAP.replace(playerId, false);
    Map<PowerUp, Location> powers = game.getRemoved();

    // updates is a list of all changes in the board state after the turn gets
    // executed (not counting the move itself).
    JsonArray updates = new JsonArray();

    // If there are any power ups to update (any that ran out after executing
    // this turn)
    for (PowerUp power : powers.keySet()) {
      JsonObject updatePart = new JsonObject();
      Location loc = powers.get(power);
      updatePart.addProperty("row", loc.getRow());
      updatePart.addProperty("col", loc.getCol());

      // If the powerup was a blackhole
      if (power instanceof BlackHole) {
        updatePart.addProperty("state", EntityTypes.NOTHING.ordinal());

        // If its invulnerability
      } else if (power instanceof Invulnerability) {
        Piece p = game.getPieceAt(loc);
        updatePart.addProperty("state", EntityTypes.PIECE.ordinal());
        updatePart.addProperty("piece", getPieceValue(p));
        if (p.getColor() == Color.WHITE) {
          updatePart.addProperty("color", true);
        } else {
          updatePart.addProperty("color", false);
        }
      }
      updates.add(updatePart);
    }

    Map<PowerObject, Location> addedPowerUp = game.getPowerObject();
    // If a power up was spawned, add the power up and its location to update
    for (PowerObject power : addedPowerUp.keySet()) {
      JsonObject updatePart = new JsonObject();
      Location loc = addedPowerUp.get(power);
      updatePart.addProperty("row", loc.getRow());
      updatePart.addProperty("col", loc.getCol());
      updatePart.addProperty("state", EntityTypes.POWER.ordinal());
      updatePart.addProperty("rarity", power.getRarity().ordinal());
      updates.add(updatePart);
    }

    /*
     * If the game is waiting for a promotion, auto-promote the piece to queen
     * and add that change to updates.
     */
    if (game.getGameState() == GameState.WAITING_FOR_PROMOTE) {
      JsonObject updatePart = new JsonObject();
      Location loc = game.executePromotionToQueen();
      updatePart.addProperty("row", loc.getRow());
      updatePart.addProperty("col", loc.getCol());
      updatePart.addProperty("state", EntityTypes.PIECE.ordinal());
      Piece p = game.getPieceAt(loc);
      updatePart.addProperty("piece", PieceIds.QUEEN.ordinal());
      if (p.getColor() == Color.WHITE) {
        updatePart.addProperty("color", true);
      } else {
        updatePart.addProperty("color", false);
      }
      updates.add(updatePart);
    }
    JsonObject response = new JsonObject();
    response.addProperty("type", MessageType.GAME_UPDATE.ordinal());
    response.add("updates", updates);

    // send valid message back
    JsonObject otherResponse = new JsonObject();
    otherResponse.addProperty("type", MessageType.GAME_UPDATE.ordinal());
    otherResponse.add("move", moveJson);
    otherResponse.add("updates", updates);
    List<PowerAction> actions = game.getActionOptions();

    // If the move captured a power up, request a power up selection;
    if (!actions.isEmpty()) {
      response.addProperty("action", Action.SELECT_POWER.ordinal());
      otherResponse.addProperty("action", Action.NONE.ordinal());
      PowerAction action1 = actions.get(0);
      response.addProperty("rarity", action1.getRarity().ordinal());
      response.addProperty("id1", action1.getId());
      PowerAction action2 = actions.get(1);
      response.addProperty("id2", action2.getId());

      // Otherwise, tell the session owner that their turn is over and tell the
      // other player
      // that their turn has started
    } else {
      response.addProperty("action", Action.NONE.ordinal());
      otherResponse.addProperty("action", Action.MOVE.ordinal());
    }
    session.getRemote().sendString(GSON.toJson(response));
    List<Integer> playerList = GAME_PLAYER_MAP.get(gameId);
    for (int i = 0; i < playerList.size(); i++) {
      if (playerList.get(i) != playerId) {
        Session otherSession = PLAYER_SESSION_MAP.get(playerList.get(i));
        otherSession.getRemote().sendString(GSON.toJson(otherResponse));
        return;
      }
    }
  }

  /**
   * Returns the integer value id of the given piece. (NOTE: this is different
   * than the rank of the piece used in game).
   *
   * @param p
   *          The piece to get the id of.
   * @return The integer id of the piece.
   */
  private int getPieceValue(Piece p) {
    if (p instanceof King) {
      return PieceIds.KING.ordinal();
    } else if (p instanceof Queen) {
      return PieceIds.QUEEN.ordinal();
    } else if (p instanceof Bishop) {
      return PieceIds.BISHOP.ordinal();
    } else if (p instanceof Rook) {
      return PieceIds.ROOK.ordinal();
    } else if (p instanceof Knight) {
      return PieceIds.KNIGHT.ordinal();
    } else {
      return PieceIds.PAWN.ordinal();
    }
  }

  /**
   * Reads a move from the given JsonObject and returns the move.
   *
   * @param moveJson
   *          JsonObject containing the a "from" and "to" JsonObject, each with
   *          their own "row" and "col" ints.
   * @return A move, starting from from.row and from.col and ending at to.row
   *         and to.col.
   */
  private Move getMove(JsonObject moveJson) {
    JsonObject from = moveJson.get("from").getAsJsonObject();
    JsonObject to = moveJson.get("to").getAsJsonObject();
    int fromRow = from.get("row").getAsInt();
    int fromCol = from.get("col").getAsInt();
    int toRow = to.get("row").getAsInt();
    int toCol = to.get("col").getAsInt();
    Location startLocation = new Location(fromRow, fromCol);
    Location endLocation = new Location(toRow, toCol);
    Move move = new Move(startLocation, endLocation);
    return move;
  }

  /**
   * Creates a game, adding in a player with the given color. In addition,
   * stores (gameId -> game), (gameId -> playerId), (playerId -> session),
   * (playerId -> name), and (playerId -> false) in the corresponding maps.
   *
   * @param session
   *          The session of the client creating the game,
   * @param received
   *          The JsonObject sent by session.
   * @throws IOException
   *           In case the response JsonObject doesn't get sent properly.
   */
  private void createGame(Session session, JsonObject received)
      throws IOException {
    Game game = new Game();
    int gameId = nextGameId;
    nextGameId++;
    GAME_ID_MAP.put(gameId, game);

    boolean colorBool = received.get("color").getAsBoolean();
    Color playerColor = Color.BLACK;
    if (colorBool) {
      playerColor = Color.WHITE;
    }
    GuiPlayer player = new GuiPlayer(playerColor);
    int playerId = nextPlayerId;
    nextPlayerId++;
    PLAYER_SESSION_MAP.put(playerId, session);
    String name = received.get("name").getAsString();
    PLAYER_NAME_MAP.put(playerId, name);
    PLAYER_DRAW_MAP.put(playerId, false);

    List<Integer> playerList = new ArrayList<>();
    playerList.add(playerId);
    GAME_PLAYER_MAP.put(gameId, playerList);

    game.addPlayer(player);

    JsonObject response = new JsonObject();
    response.addProperty("type", MessageType.CREATE_GAME.ordinal());
    response.addProperty("gameId", gameId);
    response.addProperty("playerId", playerId);

    session.getRemote().sendString(GSON.toJson(response));
  }

  /**
   * Adds a player to a currently existing game. Updates gamePlayerMap (gameId
   * -> playerId1, playerId2), adds (playerId -> session), (playerid -> name),
   * and (playerId -> false) to their respective maps as well.
   *
   * @param session
   *          Session of the client trying to join the game.
   * @param received
   *          JsonObject send by session.
   * @throws IOException
   *           If the response JsonObject fails to send properly.
   */
  private void addPlayer(Session session, JsonObject received)
      throws IOException {
    int playerId = nextPlayerId;
    nextPlayerId++;
    int gameId = received.get("gameId").getAsInt();
    Game game = GAME_ID_MAP.get(gameId);
    Color playerColor = game.getEmptyPlayerColor();
    // If there is no available player color, then this game cannot accept any
    // more players.
    if (playerColor == null) {
      JsonObject response = new JsonObject();
      response.addProperty("type", MessageType.ERROR.ordinal());
      session.getRemote().sendString(GSON.toJson(response));
      return;
    }
    GuiPlayer player = new GuiPlayer(playerColor);

    String name = received.get("name").getAsString();
    String otherName;
    List<Integer> playerList = GAME_PLAYER_MAP.get(gameId);
    // If the list size is 1, then the player can be added to the game normally.
    // Otherwise, there was an error of some sort.
    if (playerList.size() == 1) {
      playerList.add(playerId);
      GAME_PLAYER_MAP.replace(gameId, playerList);

      JsonObject responseToOther = new JsonObject();
      responseToOther.addProperty("type", MessageType.JOIN_GAME.ordinal());
      responseToOther.addProperty("name", name);
      Session otherSession = PLAYER_SESSION_MAP.get(playerList.get(0));
      otherName = PLAYER_NAME_MAP.get(playerList.get(0));
      otherSession.getRemote().sendString(GSON.toJson(responseToOther));
    } else {
      JsonObject response = new JsonObject();
      response.addProperty("type", MessageType.ILLEGAL_ACTION.ordinal());
      session.getRemote().sendString(GSON.toJson(response));
      return;
    }

    PLAYER_SESSION_MAP.put(playerId, session);
    PLAYER_NAME_MAP.put(playerId, name);
    PLAYER_DRAW_MAP.put(playerId, false);

    game.addPlayer(player);

    JsonObject response = new JsonObject();
    response.addProperty("type", MessageType.JOIN_GAME.ordinal());
    response.addProperty("playerId", playerId);
    boolean colorBool = true;
    if (playerColor == Color.BLACK) {
      colorBool = false;
    }
    response.addProperty("color", colorBool);
    response.addProperty("name", otherName);
    session.getRemote().sendString(GSON.toJson(response));
  }
}