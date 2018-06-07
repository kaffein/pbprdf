package com.stellmangreene.pbprdf

import org.joda.time.DateTime
import org.openrdf.model.URI
import org.openrdf.model.vocabulary.RDF
import org.openrdf.model.vocabulary.RDFS
import org.openrdf.repository.Repository

import com.stellmangreene.pbprdf.model.EntityUriFactory
import com.stellmangreene.pbprdf.model.Ontology
import com.stellmangreene.pbprdf.plays.EnterPlay

import com.stellmangreene.pbprdf.util.RdfOperations._

import better.files._

import com.typesafe.scalalogging.LazyLogging
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.format.DateTimeFormat
import javax.xml.datatype.DatatypeFactory

//TODO: Add triples for the current score (e.g. "10-4") -- grep -r "CURRENTLY IGNORED" src/main/scala
//TODO: Add triples for the box score, test against official box scores
//TODO: Add triples for the players on the court for each possession

/**
 * Play by play that can generate RDF and contents of a text file
 *
 * @author andrewstellman
 */
abstract class PlayByPlay extends LazyLogging {

  /** Events from the play-by-play */
  val events: Seq[Event]

  /** URI of this game */
  val gameUri: URI

  /** Name of the home team */
  val homeTeam: String

  /** Final score for the home team */
  val homeScore: String

  /** Name of the away team */
  val awayTeam: String

  /** Final score for the away team */
  val awayScore: String

  /** Game location */
  val gameLocation: Option[String]

  /** Game time */
  val gameTime: DateTime

  /** Game source (eg. filename) */
  val gameSource: String

  /** Game period information */
  val gamePeriodInfo: GamePeriodInfo

  /** returns the league (e.g. Some("WNBA")) based on GamePeriodInfo, None if unrecognized */
  def league = {
    gamePeriodInfo match {
      case GamePeriodInfo.WNBAPeriodInfo  => Some("WNBA")
      case GamePeriodInfo.NBAPeriodInfo   => Some("NBA")
      case GamePeriodInfo.NCAAWPeriodInfo => Some("NCAAW")
      case GamePeriodInfo.NCAAMPeriodInfo => Some("NCAAM")
      case _ => {
        logger.warn("Unrecognized league")
        None
      }
    }
  }

  /**
   * Add the events to an RDF repository
   *
   * @param rep
   *            Sesame repository to add the events to
   */
  def addRdf(rep: Repository) = {
    rep.addTriple(gameUri, RDF.TYPE, Ontology.GAME)
    gameLocation.foreach(location =>
      rep.addTriple(gameUri, Ontology.GAME_LOCATION, rep.getValueFactory.createLiteral(location)))
    rep.addTriple(gameUri, RDFS.LABEL, rep.getValueFactory.createLiteral(this.toString))
    val gregorianGameTime = DatatypeFactory.newInstance().newXMLGregorianCalendar(gameTime.toGregorianCalendar())
    rep.addTriple(gameUri, Ontology.GAME_TIME, rep.getValueFactory.createLiteral(gregorianGameTime))
    events.foreach(_.addRdf(rep))
    Event.addPreviousAndNextTriples(rep, events)
    addRosterBnodes(rep)
  }

  /**
   * Use the "player enters" events to build the home and away team rosters and
   * add a bnode for each roster
   *
   * @param rep
   *            Sesame repository to add the events to
   */
  protected def addRosterBnodes(rep: Repository) = {

    val homeTeamRosterBnode = rep.getValueFactory.createBNode
    val awayTeamRosterBnode = rep.getValueFactory.createBNode

    rep.addTriple(EntityUriFactory.getTeamUri(homeTeam), RDF.TYPE, Ontology.TEAM)
    rep.addTriple(gameUri, Ontology.HOME_TEAM, EntityUriFactory.getTeamUri(homeTeam))
    rep.addTriple(gameUri, Ontology.HAS_HOME_TEAM_ROSTER, homeTeamRosterBnode)
    rep.addTriple(homeTeamRosterBnode, RDF.TYPE, Ontology.ROSTER)
    rep.addTriple(homeTeamRosterBnode, Ontology.ROSTER_TEAM, EntityUriFactory.getTeamUri(homeTeam))
    rep.addTriple(homeTeamRosterBnode, RDFS.LABEL, rep.getValueFactory.createLiteral(homeTeam))

    rep.addTriple(EntityUriFactory.getTeamUri(awayTeam), RDF.TYPE, Ontology.TEAM)
    rep.addTriple(gameUri, Ontology.AWAY_TEAM, EntityUriFactory.getTeamUri(awayTeam))
    rep.addTriple(gameUri, Ontology.HAS_AWAY_TEAM_ROSTER, awayTeamRosterBnode)
    rep.addTriple(awayTeamRosterBnode, RDF.TYPE, Ontology.ROSTER)
    rep.addTriple(awayTeamRosterBnode, Ontology.ROSTER_TEAM, EntityUriFactory.getTeamUri(awayTeam))
    rep.addTriple(awayTeamRosterBnode, RDFS.LABEL, rep.getValueFactory.createLiteral(awayTeam))

    val playerTeamMap: Map[String, String] = events
      .filter(_.isInstanceOf[EnterPlay])
      .map(_.asInstanceOf[EnterPlay])
      .filter(_.playerEntering.isDefined)
      .map(enterPlay => enterPlay.playerEntering.get -> enterPlay.getTeam)
      .toMap

    val teams = playerTeamMap.values.toSeq.distinct
    if (teams.size != 2)
      logger.warn(s"Found entry plays with invalid number of teams ${teams.size} for game <${gameUri}> in ${gameSource}")

    val players = playerTeamMap.keys.toSeq.distinct
    players.foreach(player => {
      rep.addTriple(EntityUriFactory.getPlayerUri(player), RDFS.LABEL, rep.getValueFactory.createLiteral(player.trim))

      val playerTeam = playerTeamMap.get(player).get
      val playerUri = EntityUriFactory.getPlayerUri(player)
      rep.addTriple(playerUri, RDF.TYPE, Ontology.PLAYER)
      if (playerTeam == homeTeam) {
        rep.addTriple(homeTeamRosterBnode, Ontology.HAS_PLAYER, playerUri)
      } else if (playerTeam == awayTeam) {
        rep.addTriple(awayTeamRosterBnode, Ontology.HAS_PLAYER, playerUri)
      } else {
        logger.warn(s"Entry plays contain team ${playerTeam} that does match home team ${homeTeam} or away team ${awayTeam} in ${gameSource}")
      }
    })
  }

  /**
   * returns the contents of a text file representation of this play-by-play, or None if the play can't be rendered correctly
   */
  def textFileContents: Option[Seq[String]] = {
    val header = Seq(
      toString,
      s"${gameLocation.getOrElse("Unknown Location")}\t${ISODateTimeFormat.dateTime().print(gameTime)}")

    val eventLines = events.map(_.getText)

    Some(header ++ eventLines)
  }

  override def toString: String = {
    val fmt = DateTimeFormat.forPattern("YYYY-MM-dd")
    val s = s"${awayTeam} (${awayScore}) at ${homeTeam} (${homeScore}) on ${fmt.print(gameTime)}"
    if (events.isEmpty) s"Empty Game: $s"
    else {
      s"${league.getOrElse("Unrecognized league")} game: $s - ${events.size} events"
    }
  }

}