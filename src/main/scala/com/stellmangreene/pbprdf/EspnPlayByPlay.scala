package com.stellmangreene.pbprdf

import scala.language.postfixOps
import scala.util.{Try, Success, Failure}

import org.joda.time._
import org.joda.time.format._

import better.files._

import org.openrdf.model.URI

import com.stellmangreene.pbprdf.model.EntityUriFactory
import com.stellmangreene.pbprdf.util.XmlHelper

import com.typesafe.scalalogging.LazyLogging

/**
 * Read an ESPN-style play-by-play file
 * @author andrewstellman
 */
class EspnPlayByPlay(path: String, playByPlayFilename: String, gameInfoFilename: String) extends PlayByPlay with LazyLogging {

  /** Game source (eg. filename) */
  override val gameSource = playByPlayFilename

  private val playByPlayXmlFile = path / playByPlayFilename
  private val playByPlayRootElem = Try(XmlHelper.parseXml(playByPlayXmlFile.newInputStream)) match {
    case Success(s) => s
    case Failure(t: Throwable) => {
      val msg = s"Unable to read ${playByPlayXmlFile.pathAsString}: ${t.getMessage}"
      logger.error(msg)
      throw new InvalidPlayByPlayException(msg)
    }
  }
  private val divs = (playByPlayRootElem \\ "body" \\ "div")

  private val gameInfoXmlFile = path / gameInfoFilename
  private val gameInfoRootElem = Try(XmlHelper.parseXml(gameInfoXmlFile.newInputStream)) match {
    case Success(s) => s
    case Failure(t: Throwable) => {
      val msg = s"Unable to read ${gameInfoXmlFile.pathAsString}: ${t.getMessage}"
      logger.error(msg)
      throw new InvalidPlayByPlayException(msg)
    }
  }
  private val gameinfoDivs = (gameInfoRootElem \\ "body" \\ "div")

  private val awayTeamElems = XmlHelper.getElemByClassAndTag(divs, "team away", "a")

  private def getElementFromXml(clazz: String, tag: String): String = {
    Try(XmlHelper.getElemByClassAndTag(divs, clazz, tag).map(_.text).get) match {
      case Success(s) => s
      case _ => {
        val msg = s"Unable to find ${clazz} in ${playByPlayFilename}"
        logger.error(msg)
        throw new InvalidPlayByPlayException(msg)
      }
    }
  }

  def getTeamNameAndScore(teamType: String) = {
    val teamNode = XmlHelper.getElemByClassAndTag(divs, s"team $teamType", "div")
    if (teamNode.isEmpty) logMessageAndThrowException(s"Unable to find ${teamType} team name in ${playByPlayFilename}")

    val teamContainer = teamNode.get.find(_.attribute("class").mkString == "team-container")
    if (teamContainer.isEmpty) logMessageAndThrowException(s"Unable to find team-container for ${teamType} team name in ${playByPlayFilename}")

    val nameContainer = teamContainer.get \ "div" \ "div" \ "a"

    val teamHref = nameContainer.head.attribute("href")
    if (teamHref.isEmpty) logMessageAndThrowException(s"Unable to find team href for ${teamType} team name in ${playByPlayFilename}")

    val nameSpan = nameContainer \ "span"
    val name = nameSpan.find(_.attribute("class").mkString == "short-name").get.text

    val scoreContainer = teamNode.get.find(_.attribute("class").mkString == "score-container")
    if (teamContainer.isEmpty) logMessageAndThrowException(s"Unable to find score-container for ${teamType} team name in ${playByPlayFilename}")

    val score = (scoreContainer.get \ "div").text

    (name, score, teamHref.get.mkString)
  }

  private val away = getTeamNameAndScore("away")

  /** Away team name */
  override val awayTeam = away._1

  /** Away team score */
  override val awayScore = away._2

  private val home = getTeamNameAndScore("home")

  private val isWnba = home._3.toLowerCase.contains("wnba")

  override val gamePeriodInfo = if (isWnba) GamePeriodInfo.WNBAPeriodInfo else GamePeriodInfo.NBAPeriodInfo

  /** Home team name */
  override val homeTeam = home._1

  /** Home team score */
  override val homeScore = home._2

  private val dataDateSpan = XmlHelper.getElemByClassAndTag(gameinfoDivs, "game-date-time", "span")
  if (dataDateSpan.isEmpty || dataDateSpan.get.isEmpty || dataDateSpan.get.head.attribute("data-date").isEmpty)
    logMessageAndThrowException(s"Unable to find game time in ${gameInfoFilename}")
  private val timestamp = dataDateSpan.get.head.attribute("data-date")

  /** Game time */
  override val gameTime: DateTime = {
    val dataDateDiv = XmlHelper.getElemByClassAndTag(gameinfoDivs, "game-date-time", "div")
    if (dataDateDiv.isEmpty || (dataDateDiv.head \ "span").isEmpty || (dataDateDiv.get \ "span").head.attribute("data-date").isEmpty)
      logMessageAndThrowException(s"Unable to find game time and location in ${gameInfoFilename}")
    val dataDateValue = (dataDateDiv.get \ "span").head.attribute("data-date").mkString
      .replace("Z", ":00.00+0000")

    val formatter = ISODateTimeFormat.dateTime().withZone(DateTimeZone.getDefault())
    Try(formatter.parseDateTime(dataDateValue)) match {
      case Success(dateTime) => {
        dateTime
      }
      case Failure(e: Throwable) => logMessageAndThrowException(s"Unable to parse game time in ${gameInfoFilename}: ${e.getMessage}")
    }
  }

  /** Game location */
  override val gameLocation = {
    val locationDiv = XmlHelper.getElemByClassAndTag(gameinfoDivs, "caption-wrapper", "div")
    if (locationDiv.isEmpty || locationDiv.get.isEmpty || locationDiv.get.head.text.trim.isEmpty) {
      logger.warn(s"Unable get location in ${gameInfoFilename}")
      None
    } else {
      Some(locationDiv.get.head.text.trim)
    }
  }

  /** URI of this game */
  val gameUri: URI = EntityUriFactory.getGameUri(homeTeam, awayTeam, gameTime)

  /** Events from the play-by-play */
  override val events: Seq[Event] = readEvents()

  private def readEvents(): Seq[Event] = {
    logger.debug("Reading game: " + (awayTeam, awayScore, homeTeam, homeScore).toString)

    val pngRegex = """/[a-z]+\.png""".r

    /** find the image filename, needed to identify the team for each play */
    def findImageFilename(href: String): String = {
      val imgSrcs =
        (gameinfoDivs \\ "a").
          filter(_.attribute("href").mkString == href)
          .map(_ \ "img").filter(!_.isEmpty)
          .flatten
          .map(_.attribute("src").mkString)

      val matches = imgSrcs
        .map(pngRegex.findFirstIn(_))
        .filter(_.isDefined)
        .map(_.get)

      if (matches.isEmpty) logMessageAndThrowException(s"Unable to find image for $href in ${playByPlayFilename}")

      matches.head
    }

    val homeImageFilename = findImageFilename(home._3)
    val awayImageFilename = findImageFilename(away._3)

    val quarterDivs = (playByPlayRootElem \\ "li" \ "div").filter(_.attribute("id").map(_.text).getOrElse("").startsWith("gp-quarter-"))
    if (quarterDivs.size < 4) logMessageAndThrowException(s"Unable find play-by-play events (only found ${quarterDivs.size} quarters) in ${playByPlayFilename}")

    val periodsAndPlayData: Seq[(Int, scala.xml.Node)] = quarterDivs.map(quarterDiv => {
      val period = quarterDiv.attribute("id").get.text.replace("gp-quarter-", "").toInt
      period -> quarterDiv
    })
      .sortBy(_._1)

    periodsAndPlayData.groupBy(_._1).map(e => {
      val (period, nodes) = e
      val timeStampTd = (nodes.head._2 \\ "td").find(_.attribute("class").mkString == "time-stamp")
      if (timeStampTd.isEmpty) logMessageAndThrowException(s"Invalid event found in in ${playByPlayFilename}: ${nodes.head._2.mkString}")
      period -> timeStampTd.get.text
    }).toMap

    val eventsAndPeriods = periodsAndPlayData.flatMap(e => {
      val (period: Int, quarterDiv: scala.xml.Node) = e

      val playRows = quarterDiv.head \\ "tr"
      playRows.tail.map(tr => {

        def findTd(clazz: String) = {
          val td = (tr \ "td").find(_.attribute("class").mkString == clazz)
          if (td.isEmpty) logMessageAndThrowException(s"Invalid event found in in ${playByPlayFilename}: ${tr.mkString}")
          td
        }

        val gameDetails = findTd("game-details").get.text
        val timeStamp = findTd("time-stamp").get.text
        val score = findTd("combined-score").get.text.replaceAll(" ", "")

        val logo = findTd("logo")
        if (logo.isEmpty || (logo.get \ "img").isEmpty || (logo.get \ "img").head.attribute("src").isEmpty) logMessageAndThrowException(s"Invalid event found in in ${playByPlayFilename}: ${tr.mkString}")
        val logoHref = (logo.get \ "img").head.attribute("src").mkString
        val teamName =
          logoHref match {
            case s if s.contains(homeImageFilename) => homeTeam
            case s if s.contains(awayImageFilename) => awayTeam
            case _                                  => logMessageAndThrowException(s"Invalid team logo href (${logoHref}) found in in ${playByPlayFilename}: ${tr.mkString}")
          }

        (period, gameDetails, timeStamp, teamName, score)
      })
    })

    val eventData: Seq[Event] = eventsAndPeriods.zipWithIndex.map(e => {
      val ((period, play, timeStamp, teamName, score), eventIndex) = e
      Event(gameUri, playByPlayFilename, eventIndex + 1, period, timeStamp, teamName, play, score, gamePeriodInfo)
    })

    if (eventData.isEmpty)
      logger.warn(s"No events read from ${playByPlayFilename}")
    logger.debug(s"Finished reading game")

    eventData
  }

  /** log a message and throw an InvalidPlayByPlayException */
  private def logMessageAndThrowException(message: String) = {
    logger.error(message)
    throw new InvalidPlayByPlayException(message)
  }

}
