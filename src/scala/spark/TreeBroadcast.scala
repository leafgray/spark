package spark

import java.io._
import java.net._
import java.util.{Comparator, Random, UUID}

import scala.collection.mutable.{ListBuffer, Map, Set}

@serializable
class TreeBroadcast[T] (@transient var value_ : T, isLocal: Boolean) 
extends Broadcast[T] with Logging {
  
  def value = value_

  TreeBroadcast.synchronized { 
    TreeBroadcast.values.put (uuid, value_) 
  }
   
  @transient var arrayOfBytes: Array[Byte] = null
  @transient var totalBytes = -1
  @transient var totalBlocks = -1
  @transient var hasBlocks = 0

  @transient var listenPortLock = new Object
  @transient var guidePortLock = new Object
  @transient var totalBlocksLock = new Object
  @transient var hasBlocksLock = new Object
  
  @transient var listOfSources = ListBuffer[SourceInfo]()

  @transient var serveMR: ServeMultipleRequests = null 
  @transient var guideMR: GuideMultipleRequests = null 

  @transient var hostAddress = InetAddress.getLocalHost.getHostAddress
  @transient var listenPort = -1    
  @transient var guidePort = -1
  
  @transient var hasCopyInHDFS = false
  @transient var stopBroadcast = false
  
  // Must call this after all the variables have been created/initialized
  if (!isLocal) { 
    sendBroadcast 
  }

  def sendBroadcast (): Unit = {
    logInfo ("Local host address: " + hostAddress)

    // Store a persistent copy in HDFS    
    // TODO: Turned OFF for now
    // val out = new ObjectOutputStream (DfsBroadcast.openFileForWriting(uuid))
    // out.writeObject (value_)
    // out.close()    
    // TODO: Fix this at some point  
    hasCopyInHDFS = true    

    // Create a variableInfo object and store it in valueInfos
    var variableInfo = blockifyObject (value_, TreeBroadcast.BlockSize)   
    
    // Prepare the value being broadcasted
    // TODO: Refactoring and clean-up required here
    arrayOfBytes = variableInfo.arrayOfBytes
    totalBytes = variableInfo.totalBytes
    totalBlocks = variableInfo.totalBlocks
    hasBlocks = variableInfo.totalBlocks      
   
    guideMR = new GuideMultipleRequests
    guideMR.setDaemon (true)
    guideMR.start
    logInfo ("GuideMultipleRequests started...")
    
    // Must always come AFTER guideMR is created
    while (guidePort == -1) {
      guidePortLock.synchronized {
        guidePortLock.wait
      }
    }

    serveMR = new ServeMultipleRequests
    serveMR.setDaemon (true)
    serveMR.start
    logInfo ("ServeMultipleRequests started...")

    // Must always come AFTER serveMR is created
    while (listenPort == -1) {
      listenPortLock.synchronized {
        listenPortLock.wait
      }
    }

    // Must always come AFTER listenPort is created
    val masterSource =
      SourceInfo (hostAddress, listenPort, totalBlocks, totalBytes)
    listOfSources = listOfSources + masterSource

    // Register with the Tracker
    TreeBroadcast.registerValue (uuid, guidePort)  
  }
  
  private def readObject (in: ObjectInputStream): Unit = {
    in.defaultReadObject
    TreeBroadcast.synchronized {
      val cachedVal = TreeBroadcast.values.get (uuid)
      if (cachedVal != null) {
        value_ = cachedVal.asInstanceOf[T]
      } else {
        // Initializing everything because Master will only send null/0 values
        initializeSlaveVariables
        
        logInfo ("Local host address: " + hostAddress)

        serveMR = new ServeMultipleRequests
        serveMR.setDaemon (true)
        serveMR.start
        logInfo ("ServeMultipleRequests started...")
        
        val start = System.nanoTime        

        val receptionSucceeded = receiveBroadcast (uuid)
        // If does not succeed, then get from HDFS copy
        if (receptionSucceeded) {
          value_ = unBlockifyObject[T]
          TreeBroadcast.values.put (uuid, value_)
        }  else {
          val fileIn = new ObjectInputStream(DfsBroadcast.openFileForReading(uuid))
          value_ = fileIn.readObject.asInstanceOf[T]
          TreeBroadcast.values.put(uuid, value_)
          fileIn.close()
        } 
        
        val time = (System.nanoTime - start) / 1e9
        logInfo("Reading Broadcasted variable " + uuid + " took " + time + " s")                  
      }
    }
  }
  
  private def initializeSlaveVariables: Unit = {
    arrayOfBytes = null
    totalBytes = -1
    totalBlocks = -1
    hasBlocks = 0
    
    listenPortLock = new Object
    totalBlocksLock = new Object
    hasBlocksLock = new Object
    
    serveMR =  null
    
    hostAddress = InetAddress.getLocalHost.getHostAddress
    listenPort = -1
    
    stopBroadcast = false
  }
  
  private def blockifyObject (obj: T, blockSize: Int): VariableInfo = {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream (baos)
    oos.writeObject (obj)
    oos.close
    baos.close
    val byteArray = baos.toByteArray
    
    var blockNum = (byteArray.length / blockSize) 
    if (byteArray.length % blockSize != 0) 
      blockNum += 1      
      
    var variableInfo = VariableInfo (byteArray, blockNum, byteArray.length)
    variableInfo.hasBlocks = blockNum
    
    return variableInfo
  }  
  
  private def unBlockifyObject[A]: A = {
    byteArrayToObject (arrayOfBytes)
  }
  
  private def byteArrayToObject[A] (bytes: Array[Byte]): A = {
    val in = new ObjectInputStream (new ByteArrayInputStream (bytes))
    val retVal = in.readObject.asInstanceOf[A]
    in.close()
    return retVal
  }
    
  def getMasterListenPort (variableUUID: UUID): Int = {
    var clientSocketToTracker: Socket = null
    var oosTracker: ObjectOutputStream = null
    var oisTracker: ObjectInputStream = null
    
    var masterListenPort: Int = SourceInfo.TxOverGoToHDFS
    
    var retriesLeft = TreeBroadcast.MaxRetryCount
    do {
      try {  
        // Connect to the tracker to find out the guide 
        clientSocketToTracker = 
          new Socket(TreeBroadcast.MasterHostAddress, TreeBroadcast.MasterTrackerPort)  
        oosTracker = 
          new ObjectOutputStream (clientSocketToTracker.getOutputStream)
        oosTracker.flush()
        oisTracker = 
          new ObjectInputStream (clientSocketToTracker.getInputStream)
      
        // Send UUID and receive masterListenPort
        oosTracker.writeObject (uuid)
        oosTracker.flush()
        masterListenPort = oisTracker.readObject.asInstanceOf[Int]
      } catch {
        case e: Exception => { 
          logInfo ("getMasterListenPort had a " + e)
        }
      } finally {   
        if (oisTracker != null) { 
          oisTracker.close() 
        }
        if (oosTracker != null) { 
          oosTracker.close() 
        }
        if (clientSocketToTracker != null) { 
          clientSocketToTracker.close() 
        }
      }
      retriesLeft -= 1     

      Thread.sleep (TreeBroadcast.ranGen.nextInt (
        TreeBroadcast.MaxKnockInterval - TreeBroadcast.MinKnockInterval) +
        TreeBroadcast.MinKnockInterval)

    } while (retriesLeft > 0 && masterListenPort == SourceInfo.TxNotStartedRetry)

    logInfo ("Got this guidePort from Tracker: " + masterListenPort)
    return masterListenPort
  }
  
  def receiveBroadcast (variableUUID: UUID): Boolean = {
    val masterListenPort = getMasterListenPort (variableUUID) 

    if (masterListenPort == SourceInfo.TxOverGoToHDFS ||
        masterListenPort == SourceInfo.TxNotStartedRetry) { 
      // TODO: SourceInfo.TxNotStartedRetry is not really in use because we go
      // to HDFS anyway when receiveBroadcast returns false
      return false
    }

    // Wait until hostAddress and listenPort are created by the 
    // ServeMultipleRequests thread
    while (listenPort == -1) { 
      listenPortLock.synchronized {
        listenPortLock.wait 
      }
    } 

    var clientSocketToMaster: Socket = null
    var oosMaster: ObjectOutputStream = null
    var oisMaster: ObjectInputStream = null

    // Connect and receive broadcast from the specified source, retrying the
    // specified number of times in case of failures
    var retriesLeft = TreeBroadcast.MaxRetryCount
    do {      
      // Connect to Master and send this worker's Information
      clientSocketToMaster = 
        new Socket(TreeBroadcast.MasterHostAddress, masterListenPort)  
      // TODO: Guiding object connection is reusable
      oosMaster = 
        new ObjectOutputStream (clientSocketToMaster.getOutputStream)
      oosMaster.flush()
      oisMaster = 
        new ObjectInputStream (clientSocketToMaster.getInputStream)
      
      logInfo ("Connected to Master's guiding object")

      // Send local source information
      oosMaster.writeObject(SourceInfo (hostAddress, listenPort, 
        SourceInfo.UnusedParam, SourceInfo.UnusedParam))
      oosMaster.flush()

      // Receive source information from Master        
      // Receive source information from Master        
      var sourceInfo = oisMaster.readObject.asInstanceOf[SourceInfo]
      totalBlocks = sourceInfo.totalBlocks
      totalBlocksLock.synchronized {
        totalBlocksLock.notifyAll
      }
      totalBytes = sourceInfo.totalBytes
      arrayOfBytes = new Array[Byte] (totalBytes)

      logInfo ("Received SourceInfo from Master:" + sourceInfo + " My Port: " + listenPort)    

      val start = System.nanoTime  
      val receptionSucceeded = receiveSingleTransmission (sourceInfo)
      val time = (System.nanoTime - start) / 1e9      
      
      // Updating some statistics in sourceInfo. Master will be using them later
      if (!receptionSucceeded) { 
        sourceInfo.receptionFailed = true 
      }

      // Send back statistics to the Master
      oosMaster.writeObject (sourceInfo) 
    
      if (oisMaster != null) {
        oisMaster.close()
      }
      if (oosMaster != null) {
        oosMaster.close()
      }
      if (clientSocketToMaster != null) {
        clientSocketToMaster.close()
      }
      
      retriesLeft -= 1
    } while (retriesLeft > 0 && hasBlocks < totalBlocks)
    
    return (hasBlocks == totalBlocks)
  }

  // Tries to receive broadcast from the source and returns Boolean status.
  // This might be called multiple times to retry a defined number of times.
  private def receiveSingleTransmission(sourceInfo: SourceInfo): Boolean = {
    var clientSocketToSource: Socket = null    
    var oosSource: ObjectOutputStream = null
    var oisSource: ObjectInputStream = null
    
    var receptionSucceeded = false    
    try {
      // Connect to the source to get the object itself
      clientSocketToSource = 
        new Socket (sourceInfo.hostAddress, sourceInfo.listenPort)        
      oosSource = 
        new ObjectOutputStream (clientSocketToSource.getOutputStream)
      oosSource.flush()
      oisSource = 
        new ObjectInputStream (clientSocketToSource.getInputStream)
        
      logInfo ("Inside receiveSingleTransmission")
      logInfo ("totalBlocks: "+ totalBlocks + " " + "hasBlocks: " + hasBlocks)
      
      // Send the range       
      oosSource.writeObject((hasBlocks, totalBlocks))
      oosSource.flush()
      
      for (i <- hasBlocks until totalBlocks) {
        // Calculate range to send in bytes
        val fromByte = i * TreeBroadcast.BlockSize
        var untilByte = (i + 1) * TreeBroadcast.BlockSize
        if (untilByte > totalBytes) {
          untilByte = totalBytes
        }
        val numBytes = untilByte - fromByte      
      
        val recvStartTime = System.currentTimeMillis
        oisSource.readFully(arrayOfBytes, fromByte, numBytes)
        val receptionTime = (System.currentTimeMillis - recvStartTime)
        
        logInfo ("Received block: " + i + " from " + sourceInfo + " in " + receptionTime + " millis.")

        hasBlocks += 1
        // Set to true if at least one block is received
        receptionSucceeded = true
        hasBlocksLock.synchronized {
          hasBlocksLock.notifyAll
        }
      } 
    } catch {
      case e: Exception => { 
        logInfo ("receiveSingleTransmission had a " + e)
      }
    } finally {    
      if (oisSource != null) { 
        oisSource.close() 
      }
      if (oosSource != null) { 
        oosSource.close() 
      }
      if (clientSocketToSource != null) { 
        clientSocketToSource.close() 
      }
    }
          
    return receptionSucceeded
  } 

  class GuideMultipleRequests
  extends Thread with Logging {
    // Keep track of sources that have completed reception
    private var setOfCompletedSources = Set[SourceInfo] ()
  
    override def run: Unit = {
      var threadPool = Broadcast.newDaemonCachedThreadPool
      var serverSocket: ServerSocket = null

      serverSocket = new ServerSocket (0)
      guidePort = serverSocket.getLocalPort
      logInfo ("GuideMultipleRequests => " + serverSocket + " " + guidePort)
      
      guidePortLock.synchronized {
        guidePortLock.notifyAll
      }

      try {
        // Don't stop until there is a copy in HDFS
        while (!stopBroadcast || !hasCopyInHDFS) {
          var clientSocket: Socket = null
          try {
            serverSocket.setSoTimeout (TreeBroadcast.ServerSocketTimeout)
            clientSocket = serverSocket.accept
          } catch {
            case e: Exception => { 
              logInfo ("GuideMultipleRequests Timeout.")
              
              // Stop broadcast if at least one worker has connected and
              // everyone connected so far are done. Comparing with
              // listOfSources.size - 1, because it includes the Guide itself
              if (listOfSources.size > 1 &&
                setOfCompletedSources.size == listOfSources.size - 1) {
                stopBroadcast = true
              }              
            }
          }
          if (clientSocket != null) {
            logInfo ("Guide: Accepted new client connection: " + clientSocket)
            try {            
              threadPool.execute (new GuideSingleRequest (clientSocket))
            } catch {
              // In failure, close() the socket here; else, the thread will close() it
              case ioe: IOException => clientSocket.close()
            }
          }
        }
        
        logInfo ("Sending stopBroadcast notifications...")
        sendStopBroadcastNotifications
                
        TreeBroadcast.unregisterValue (uuid)
      } finally {
        if (serverSocket != null) {
          logInfo ("GuideMultipleRequests now stopping...")
          serverSocket.close()
        }
      }
      
      // Shutdown the thread pool
      threadPool.shutdown      
    }
    
    private def sendStopBroadcastNotifications: Unit = {
      listOfSources.synchronized {
        var listIter = listOfSources.iterator        
        while (listIter.hasNext) {
          var sourceInfo = listIter.next

          var guideSocketToSource: Socket = null
          var gosSource: ObjectOutputStream = null
          var gisSource: ObjectInputStream = null
        
          try {
            // Connect to the source
            guideSocketToSource =
              new Socket (sourceInfo.hostAddress, sourceInfo.listenPort)
            gosSource =
              new ObjectOutputStream (guideSocketToSource.getOutputStream)
            gosSource.flush()
            gisSource =
              new ObjectInputStream (guideSocketToSource.getInputStream)
            
            // Send stopBroadcast signal. Range = SourceInfo.StopBroadcast*2
            gosSource.writeObject ((SourceInfo.StopBroadcast, 
              SourceInfo.StopBroadcast))
            gosSource.flush()
          } catch {
            case e: Exception => { 
              logInfo ("sendStopBroadcastNotifications had a " + e)
            }
          } finally {
            if (gisSource != null) {
              gisSource.close()
            }
            if (gosSource != null) {
              gosSource.close()
            }
            if (guideSocketToSource != null) {
              guideSocketToSource.close()
            }
          }
        }
      }
    }
        
    class GuideSingleRequest (val clientSocket: Socket) 
    extends Thread with Logging {
      private val oos = new ObjectOutputStream (clientSocket.getOutputStream)
      oos.flush()
      private val ois = new ObjectInputStream (clientSocket.getInputStream)

      private var selectedSourceInfo: SourceInfo = null
      private var thisWorkerInfo:SourceInfo = null
      
      override def run: Unit = {
        try {
          logInfo ("new GuideSingleRequest is running")
          // Connecting worker is sending in its hostAddress and listenPort it will 
          // be listening to. Other fields are invalid (SourceInfo.UnusedParam)
          var sourceInfo = ois.readObject.asInstanceOf[SourceInfo]
          
          listOfSources.synchronized {
            // Select a suitable source and send it back to the worker
            selectedSourceInfo = selectSuitableSource (sourceInfo)
            logInfo ("Sending selectedSourceInfo: " + selectedSourceInfo)
            oos.writeObject (selectedSourceInfo)
            oos.flush()

            // Add this new (if it can finish) source to the list of sources
            thisWorkerInfo = SourceInfo (sourceInfo.hostAddress, 
              sourceInfo.listenPort, totalBlocks, totalBytes)
            logInfo ("Adding possible new source to listOfSources: " + thisWorkerInfo)    
            listOfSources = listOfSources + thisWorkerInfo
          }

          // Wait till the whole transfer is done. Then receive and update source 
          // statistics in listOfSources
          sourceInfo = ois.readObject.asInstanceOf[SourceInfo]

          listOfSources.synchronized {
            // This should work since SourceInfo is a case class
            assert (listOfSources.contains (selectedSourceInfo))
            
            // Remove first
            listOfSources = listOfSources - selectedSourceInfo
            // TODO: Removing a source based on just one failure notification!

            // Update sourceInfo and put it back in, IF reception succeeded
            if (!sourceInfo.receptionFailed) {
              // Add thisWorkerInfo to sources that have completed reception
              setOfCompletedSources.synchronized {
                setOfCompletedSources += thisWorkerInfo
              }
                            
              selectedSourceInfo.currentLeechers -= 1
              
              // Put it back 
              listOfSources = listOfSources + selectedSourceInfo
            }                        
          }      
        } catch {
          // If something went wrong, e.g., the worker at the other end died etc. 
          // then close() everything up
          case e: Exception => { 
            // Assuming that exception caused due to receiver worker failure.
            // Remove failed worker from listOfSources and update leecherCount of 
            // corresponding source worker
            listOfSources.synchronized {
              if (selectedSourceInfo != null) {
                // Remove first
                listOfSources = listOfSources - selectedSourceInfo
                // Update leecher count and put it back in
                selectedSourceInfo.currentLeechers -= 1
                listOfSources = listOfSources + selectedSourceInfo
              }
              
              // Remove thisWorkerInfo
              if (listOfSources != null) { 
                listOfSources = listOfSources - thisWorkerInfo
              }
            }      
          }
        } finally {
          ois.close()
          oos.close()
          clientSocket.close()
        }
      }
      
      // TODO: Caller must have a synchronized block on listOfSources
      // TODO: If a worker fails to get the broadcasted variable from a source 
      // and comes back to the Master, this function might choose the worker 
      // itself as a source to create a dependency cycle (this worker was put 
      // into listOfSources as a streming source when it first arrived). The 
      // length of this cycle can be arbitrarily long. 
      private def selectSuitableSource(skipSourceInfo: SourceInfo): SourceInfo = {
        // Select one with the most leechers. This will level-wise fill the tree
        
        var maxLeechers = -1
        var selectedSource: SourceInfo = null

        listOfSources.foreach { source => 
          if (source != skipSourceInfo &&
            source.currentLeechers < TreeBroadcast.MaxDegree &&
            source.currentLeechers > maxLeechers) {
              selectedSource = source
              maxLeechers = source.currentLeechers
            }
        }        

        // Update leecher count
        selectedSource.currentLeechers += 1

        return selectedSource
      }
    }    
  }

  class ServeMultipleRequests
  extends Thread with Logging {
    override def run: Unit = {
      var threadPool = Broadcast.newDaemonCachedThreadPool
      var serverSocket: ServerSocket = null

      serverSocket = new ServerSocket (0) 
      listenPort = serverSocket.getLocalPort
      logInfo ("ServeMultipleRequests started with " + serverSocket)
      
      listenPortLock.synchronized {
        listenPortLock.notifyAll
      }
            
      try {
        while (!stopBroadcast) {
          var clientSocket: Socket = null
          try {
            serverSocket.setSoTimeout (TreeBroadcast.ServerSocketTimeout)
            clientSocket = serverSocket.accept
          } catch {
            case e: Exception => { 
              logInfo ("ServeMultipleRequests Timeout.") 
            }
          }
          if (clientSocket != null) {
            logInfo ("Serve: Accepted new client connection: " + clientSocket)
            try {            
              threadPool.execute (new ServeSingleRequest (clientSocket))
            } catch {
              // In failure, close() socket here; else, the thread will close() it
              case ioe: IOException => clientSocket.close()
            }
          }
        }
      } finally {
        if (serverSocket != null) {
          logInfo ("ServeMultipleRequests now stopping...") 
          serverSocket.close() 
        }
      }
      
      // Shutdown the thread pool
      threadPool.shutdown      
    }
    
    class ServeSingleRequest (val clientSocket: Socket) 
    extends Thread with Logging {
      private val oos = new ObjectOutputStream (clientSocket.getOutputStream)
      oos.flush()
      private val ois = new ObjectInputStream (clientSocket.getInputStream)
      
      private var sendFrom = 0
      private var sendUntil = totalBlocks
      
      override def run: Unit = {
        try {
          logInfo ("new ServeSingleRequest is running")
          
          // Receive range to send
          var rangeToSend = ois.readObject.asInstanceOf[(Int, Int)]
          sendFrom = rangeToSend._1
          sendUntil = rangeToSend._2
          
          if (sendFrom == SourceInfo.StopBroadcast && 
            sendUntil == SourceInfo.StopBroadcast) {
            stopBroadcast = true
          } else {
            // Carry on
            sendBlock
          }
        } catch {
          // If something went wrong, e.g., the worker at the other end died etc. 
          // then close() everything up
          case e: Exception => { 
            logInfo ("ServeSingleRequest had a " + e)
          }
        } finally {
          logInfo ("ServeSingleRequest is closing streams and sockets")
          ois.close()
          oos.close()
          clientSocket.close()
        }
      }

      private def sendBlock: Unit = {
        // Wait till receiving the SourceInfo from Master
        while (totalBlocks == -1) { 
          totalBlocksLock.synchronized {
            totalBlocksLock.wait
          }
        }

        for (i <- sendFrom until sendUntil) {
          while (i == hasBlocks) { 
            hasBlocksLock.synchronized {
              hasBlocksLock.wait
            }
          }
          try {
            // Calculate range to send in bytes
            val fromByte = i * TreeBroadcast.BlockSize
            var untilByte = (i + 1) * TreeBroadcast.BlockSize
            if (untilByte > totalBytes) {
              untilByte = totalBytes
            }
            val numBytes = untilByte - fromByte
          
            oos.write (arrayOfBytes, fromByte, numBytes)
            oos.flush
          } catch {
            case e: Exception => { 
              logInfo ("sendObject had a " + e)
            }
          }
          logInfo ("Sent block: " + i + " to " + clientSocket)
        }
      }    
    } 
  }  
}

class TreeBroadcastFactory 
extends BroadcastFactory {
  def initialize (isMaster: Boolean) = TreeBroadcast.initialize (isMaster)
  def newBroadcast[T] (value_ : T, isLocal: Boolean) = 
    new TreeBroadcast[T] (value_, isLocal)
}

private object TreeBroadcast
extends Logging {
  val values = Cache.newKeySpace()

  var valueToGuidePortMap = Map[UUID, Int] ()
  
  // Random number generator
  var ranGen = new Random

  private var initialized = false
  private var isMaster_ = false

  private var MasterHostAddress_ = InetAddress.getLocalHost.getHostAddress
  private var MasterTrackerPort_ : Int = 22222
  private var BlockSize_ : Int = 4096 * 1024
  private var MaxDegree_ : Int = 2
  private var MaxRetryCount_ : Int = 2

  private var TrackerSocketTimeout_ : Int = 50000
  private var ServerSocketTimeout_ : Int = 10000

  private var trackMV: TrackMultipleValues = null

  private var MinKnockInterval_ = 500
  private var MaxKnockInterval_ = 999

  def initialize (isMaster__ : Boolean): Unit = {
    synchronized {
      if (!initialized) {
        MasterHostAddress_ = 
          System.getProperty ("spark.broadcast.masterHostAddress", "127.0.0.1")
        MasterTrackerPort_ = System.getProperty (
          "spark.broadcast.masterTrackerPort", "22222").toInt
        BlockSize_ = System.getProperty (
          "spark.broadcast.blockSize", "4096").toInt * 1024
        MaxDegree_ = System.getProperty (
          "spark.broadcast.maxDegree", "2").toInt
        MaxRetryCount_ = System.getProperty (
          "spark.broadcast.maxRetryCount", "2").toInt          

        TrackerSocketTimeout_ = System.getProperty (
          "spark.broadcast.trackerSocketTimeout", "50000").toInt          
        ServerSocketTimeout_ = System.getProperty (
          "spark.broadcast.serverSocketTimeout", "10000").toInt          

        MinKnockInterval_ = System.getProperty (
          "spark.broadcast.minKnockInterval", "500").toInt
        MaxKnockInterval_ = System.getProperty (
          "spark.broadcast.maxKnockInterval", "999").toInt

        isMaster_ = isMaster__        
                  
        if (isMaster) {
          trackMV = new TrackMultipleValues
          trackMV.setDaemon (true)
          trackMV.start
          // TODO: Logging the following line makes the Spark framework ID not 
          // getting logged, cause it calls logInfo before log4j is initialized
          // logInfo ("TrackMultipleValues started...")         
        }
                  
        // Initialize DfsBroadcast to be used for broadcast variable persistence
        DfsBroadcast.initialize

        initialized = true
      }
    }
  }
   
  def MasterHostAddress = MasterHostAddress_
  def MasterTrackerPort = MasterTrackerPort_
  def BlockSize = BlockSize_
  def MaxDegree = MaxDegree_
  def MaxRetryCount = MaxRetryCount_

  def TrackerSocketTimeout = TrackerSocketTimeout_
  def ServerSocketTimeout = ServerSocketTimeout_

  def isMaster = isMaster_ 
  
  def MinKnockInterval = MinKnockInterval_
  def MaxKnockInterval = MaxKnockInterval_

  def registerValue (uuid: UUID, guidePort: Int): Unit = {
    valueToGuidePortMap.synchronized {    
      valueToGuidePortMap += (uuid -> guidePort)
      logInfo ("New value registered with the Tracker " + valueToGuidePortMap)             
    }
  }
  
  def unregisterValue (uuid: UUID): Unit = {
    valueToGuidePortMap.synchronized {
      valueToGuidePortMap (uuid) = SourceInfo.TxOverGoToHDFS
      logInfo ("Value unregistered from the Tracker " + valueToGuidePortMap)             
    }
  }
  
  class TrackMultipleValues
  extends Thread with Logging {
    override def run: Unit = {
      var threadPool = Broadcast.newDaemonCachedThreadPool
      var serverSocket: ServerSocket = null
      
      serverSocket = new ServerSocket (TreeBroadcast.MasterTrackerPort)
      logInfo ("TrackMultipleValues" + serverSocket)      
      
      try {
        while (true) {
          var clientSocket: Socket = null
          try {
            serverSocket.setSoTimeout (TrackerSocketTimeout)
            clientSocket = serverSocket.accept
          } catch {
            case e: Exception => { 
              logInfo ("TrackMultipleValues Timeout. Stopping listening...") 
            }
          }

          if (clientSocket != null) {
            try {            
              threadPool.execute (new Thread {
                override def run: Unit = {
                  val oos = new ObjectOutputStream (clientSocket.getOutputStream)
                  oos.flush()
                  val ois = new ObjectInputStream (clientSocket.getInputStream)
                  try {
                    val uuid = ois.readObject.asInstanceOf[UUID]
                    var guidePort = 
                      if (valueToGuidePortMap.contains (uuid)) {
                        valueToGuidePortMap (uuid)
                      } else SourceInfo.TxNotStartedRetry
                    logInfo ("TrackMultipleValues: Got new request: " + clientSocket + " for " + uuid + " : " + guidePort)                    
                    oos.writeObject (guidePort)
                  } catch {
                    case e: Exception => { 
                      logInfo ("TrackMultipleValues had a " + e)
                    }
                  } finally {
                    ois.close()
                    oos.close()
                    clientSocket.close()
                  }
                }
              })
            } catch {
              // In failure, close() socket here; else, client thread will close()
              case ioe: IOException => clientSocket.close()
            }
          }
        }
      } finally {
        serverSocket.close()
      }
      
      // Shutdown the thread pool
      threadPool.shutdown      
    }
  }
}
