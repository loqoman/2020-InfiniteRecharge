package com.spartronics4915.frc2020.commands;

import java.util.function.BooleanSupplier;

import com.spartronics4915.frc2020.Constants;
import com.spartronics4915.frc2020.CoordSysMgr2020;
import com.spartronics4915.frc2020.commands.IndexerCommands.LoadToLauncher;
import com.spartronics4915.frc2020.subsystems.Indexer;
import com.spartronics4915.frc2020.subsystems.Launcher;
import com.spartronics4915.lib.math.twodim.geometry.Pose2d;
import com.spartronics4915.lib.math.twodim.geometry.Rotation2d;
import com.spartronics4915.lib.math.threedim.Vec3;
import com.spartronics4915.lib.util.Units;
import com.spartronics4915.lib.subsystems.estimator.RobotStateMap;

import edu.wpi.first.wpilibj2.command.CommandBase;
import edu.wpi.first.wpilibj2.command.InstantCommand;
import edu.wpi.first.wpilibj2.command.ParallelCommandGroup;
import edu.wpi.first.wpilibj2.command.RunCommand;
import edu.wpi.first.wpilibj2.command.SequentialCommandGroup;
import edu.wpi.first.wpilibj2.command.WaitUntilCommand;

public class LauncherCommands
{
    private final Launcher mLauncher;
    private final Indexer mIndexer;
    private final IndexerCommands mIndexerCommands;
    final CoordSysMgr2020 mCoordSysMgr;
    private final RobotStateMap mStateMap;
    private final Pose2d mMatchTargetMeters;
    private final Vec3 mMatchTargetInches;

    public LauncherCommands(Launcher launcher, IndexerCommands indexerCommands, 
                            RobotStateMap stateMap, CoordSysMgr2020 csmgr)
    {
        mLauncher = launcher;
        mIndexerCommands = indexerCommands;
        mIndexer = mIndexerCommands.getIndexer();
        mStateMap = stateMap;
        mCoordSysMgr = csmgr;
        // our target is always on the opposite side of the field.  This
        // works for both Alliances since the field is symmetric and we
        // use the same coordinate system (rotated by 180) on the Dashboard.
        // (almost, since RSM is meters and dashboard is inches).
        mMatchTargetInches = new Vec3(
                                Constants.Vision.kAllianceGoalCoords[0],
                                Constants.Vision.kAllianceGoalCoords[1],
                                0); // on ground/robot origin

        mMatchTargetMeters = new Pose2d(
            Units.inchesToMeters(Constants.Vision.kAllianceGoalCoords[0]),
            Units.inchesToMeters(Constants.Vision.kAllianceGoalCoords[1]),
                                  Rotation2d.fromDegrees(180));
    }

    public Launcher getLauncher()
    {
        return mLauncher;
    }

    public class TargetAndShoot extends CommandBase
    {
        public Pose2d mTarget;
        public TargetAndShoot(Pose2d target)
        {
            mTarget = target;
            addRequirements(mLauncher);
        }

        // Called every time the scheduler runs while the command is scheduled.
        // Default isFinished (true) is okay since we assume we'll be 
        // interrupted by button-release.
        @Override
        public void execute()
        {
            double distance = trackTarget();
            mLauncher.runFlywheel(mLauncher.calcRPS(distance));
        }

        @Override
        public void end(boolean interrupted)
        {
            mLauncher.stopTurret();
        }
    }

    public class TrackPassively extends CommandBase
    {
        public TrackPassively()
        {
            addRequirements(mLauncher);
        }

        // Called when the command is initially scheduled.
        @Override
        public void initialize()
        {
        }

        // Called every time the scheduler runs while the command is scheduled.
        @Override
        public void execute()
        {
            trackTarget();
        }
    }

    public class Zero extends CommandBase
    {
        public Zero()
        {
            addRequirements(mLauncher);
        }

        @Override
        public void execute()
        {
            mLauncher.zeroTurret();
        }

        @Override
        public boolean isFinished()
        {
            return mLauncher.isZeroed();
        }
    }

    /**
     * @return Distance to the target in meters
     */
    private double trackTarget()
    {
        Pose2d fieldToTurret = mStateMap.getLatestFieldToVehicle()
            .transformBy(Constants.Launcher.kRobotToTurret);
        Pose2d turretToTarget = fieldToTurret.inFrameReferenceOf(mMatchTargetMeters);
        Rotation2d fieldAnglePointingToTarget = new Rotation2d(
                                turretToTarget.getTranslation().getX(), 
                                turretToTarget.getTranslation().getY(), 
                                true);
        Rotation2d turretAngle = fieldAnglePointingToTarget.rotateBy(fieldToTurret.getRotation().inverse());
        double distance = mMatchTargetMeters.distance(fieldToTurret);
        mLauncher.adjustHood(mLauncher.calcPitch(distance));
        mLauncher.turnTurret(turretAngle);
        return distance;
    }

    private double trackTargetAlt()
    {
        // We assume that mCoordSysMgr is up-to-date - this includes
        //  - robot's pose
        //  - turret rotation
        // 
        // Compute the vector from turret origin on field to target
        // 
        Vec3 targetPointInMnt = mCoordSysMgr.fieldPointToMount(mMatchTargetInches);
        double angle = targetPointInMnt.angleOnXYPlane();
        double dist = targetPointInMnt.length();
        if(angle > -45 && angle < 45)
        {
            mLauncher.adjustHood(mLauncher.calcPitch(dist));
            mLauncher.turnTurret(angle);
        }
        return dist;
    }

    /*
     * Command for testing, runs flywheel at a given RPS
     * !DO NOT MAKE THE RPS MORE THAN 90!
     */
    public class ShootBallTest extends CommandBase
    {
        // You should only use one subsystem per command. If multiple are needed, use a
        // CommandGroup.
        public ShootBallTest()
        {
            addRequirements(mLauncher);
        }

        // Called every time the scheduler runs while the command is scheduled.
        @Override
        public void execute()
        {
            mLauncher.runFlywheel((double) mLauncher.dashboardGetNumber("flywheelRPSSlider", 0));
            mLauncher.adjustHood(
                Rotation2d.fromDegrees((double) mLauncher.dashboardGetNumber("hoodAngleSlider", 0)));
            mLauncher.turnTurret(Rotation2d.fromDegrees((double) mLauncher.dashboardGetNumber("turretAngleSlider", 0)));
        }

        // Called once the command ends or is interrupted.
        @Override
        public void end(boolean interrupted)
        {
            mLauncher.reset();
        }
    }

    // XXX: subclass CommandBase so we don't need the launcher
    public class WaitForFlywheel extends WaitUntilCommand
    {
        public WaitForFlywheel(Launcher launcher)
        {
            super(() -> launcher.isFlywheelSpun());
        }
    }

    /*
     * Command for testing, runs flywheel at a given RPS
     * !DO NOT MAKE THE RPS MORE THAN 90!
     */
    public class ShootBallTestWithDistance extends CommandBase
    {
        // You should only use one subsystem per command. If multiple are needed, use a
        // CommandGroup.
        public ShootBallTestWithDistance()
        {
            addRequirements(mLauncher);
        }

        // Called every time the scheduler runs while the command is scheduled.
        @Override
        public void execute()
        {
            double dist = (double) mLauncher.dashboardGetNumber("targetDistanceSlider", 120);
            mLauncher.runFlywheel(mLauncher.calcRPS(dist));
            mLauncher.adjustHood(mLauncher.calcPitch(dist));
        }

        // Returns true when the command should end.
        @Override
        public boolean isFinished()
        {
            return false;
        }

        // Called once the command ends or is interrupted.
        @Override
        public void end(boolean interrupted)
        {
            //mLauncher.reset();
        }
    }

    public class ShootingTest extends ParallelCommandGroup
    {
        public ShootingTest()
        {
            addCommands(new ShootBallTest(),
                mIndexerCommands.new LoadToLauncher(mIndexer, 4));
        }
    }

    public class ShootingCalculatedTest extends ParallelCommandGroup
    {
        public ShootingCalculatedTest()
        {
            addCommands(new ShootBallTestWithDistance(),
                mIndexerCommands.new LoadToLauncher(mIndexer, 4));
        }
    }

    public class TurretTest extends CommandBase
    {
        // You should only use one subsystem per command. If multiple are needed, use a
        // CommandGroup.
        public TurretTest()
        {
            addRequirements(mLauncher);
        }

        // Called when the command is initially scheduled.
        @Override
        public void initialize()
        {
        }

        // Called every time the scheduler runs while the command is scheduled.
        @Override
        public void execute()
        {
            double degrees = (double) mLauncher.dashboardGetNumber("turretAngleSlider", 0);
            mLauncher.turnTurret(Rotation2d.fromDegrees(degrees));
        }

        // Returns true when the command should end.
        @Override
        public boolean isFinished()
        {
            return false;
        }

        // Called once the command ends or is interrupted.
        @Override
        public void end(boolean interrupted)
        {
        }
    }

    public class HoodTest extends CommandBase
    {
        // You should only use one subsystem per command. If multiple are needed, use a
        // CommandGroup.
        public HoodTest()
        {
            addRequirements(mLauncher);
        }

        // Called every time the scheduler runs while the command is scheduled.
        @Override
        public void execute()
        {
            mLauncher.adjustHood(
            Rotation2d.fromDegrees((double) mLauncher.dashboardGetNumber("hoodAngleSlider", 0)));
        }

        // Returns true when the command should end.
        @Override
        public boolean isFinished()
        {
            return false;
        }

        // Called once the command ends or is interrupted.
        @Override
        public void end(boolean interrupted)
        {
            mLauncher.reset();
        }
    }
}
