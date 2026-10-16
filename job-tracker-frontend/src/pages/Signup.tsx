import { useForm } from "react-hook-form";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import type { SignupRequest } from "../types/User";

export default function Signup() {
  const { register, handleSubmit, formState: { errors } } = useForm<SignupRequest>();
  const { register: signup } = useAuth();
  const navigate = useNavigate();

  const onSubmit = async (data: SignupRequest) => {
    try { await signup(data); navigate("/dashboard"); }
    catch (e) { alert(e instanceof Error ? e.message : "Registration failed"); }
  };

  return (
    <div className="auth-page">
      <form className="form card auth-card" onSubmit={handleSubmit(onSubmit)}>
        <h1>Create Account</h1>
        <label>Name<input {...register("name", { required: "Name is required" })} /></label>
        {errors.name && <small className="error">{errors.name.message}</small>}
        <label>Email<input type="email" {...register("email", { required: "Email is required" })} /></label>
        {errors.email && <small className="error">{errors.email.message}</small>}
        <label>Phone<input {...register("phone")} /></label>
        <label>Password<input type="password" {...register("password", { required: "Password is required", minLength: { value: 6, message: "Minimum 6 characters" } })} /></label>
        {errors.password && <small className="error">{errors.password.message}</small>}
        <button className="button">Create Account</button>
        <p>Already registered? <Link to="/signin">Sign in</Link></p>
      </form>
    </div>
  );
}
